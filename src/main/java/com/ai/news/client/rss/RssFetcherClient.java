package com.ai.news.client.rss;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;
import org.xml.sax.InputSource;

import com.ai.news.config.RssProperties;
import com.ai.news.model.dto.NewsItem;
import com.ai.news.util.NewsDuplicateUtil;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import org.springframework.beans.factory.annotation.Autowired;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * RSS/Atom 新闻抓取客户端。
 *
 * <p>该客户端负责并发读取启用的新闻源、解析订阅内容、清洗正文、过滤时间窗口，
 * 并交由去重工具和区域选择工具生成最终新闻列表。</p>
 */
@Component
@Slf4j
public class RssFetcherClient {

    /**
     * 用于发送 RSS HTTP 请求的客户端。
     */
    private final HttpClient httpClient;

    /**
     * RSS 抓取、过滤和并发控制配置。
     */
    private final RssProperties properties;

    /**
     * 根据 RSS 配置创建 HTTP 客户端。
     *
     * @param properties RSS 抓取配置
     */
    @Autowired
    public RssFetcherClient(RssProperties properties) {
        // 使用配置的连接超时创建生产 HTTP 客户端，并允许正常跟随重定向。
        this(properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    /**
     * 创建可注入自定义 HTTP 客户端的抓取器，主要供测试或特殊网络环境使用。
     *
     * @param properties RSS 抓取配置
     * @param httpClient HTTP 客户端
     */
    RssFetcherClient(RssProperties properties, HttpClient httpClient) {
        // 保存配置和 HTTP 客户端，便于生产环境与测试环境复用同一抓取逻辑。
        this.properties = properties;
        this.httpClient = httpClient;
    }

    /**
     * 并发抓取所有启用的来源，并返回时间窗口内、去重且符合区域比例的新闻。
     *
     * @param now 当前时间，用于计算新闻时间窗口
     * @return 可供 AI 摘要处理的新闻列表
     */
    public List<NewsItem> fetchRecent(Instant now) {
        // 以当前时间向前计算新闻有效时间窗口的起点。
        Instant cutoff = now.minus(Duration.ofHours(properties.getWindowHours()));
        List<NewsItem> items = new ArrayList<>();
        // 只为启用的来源创建任务，避免访问已关闭或未配置完成的地址。
        List<RssProperties.Source> enabledSources = properties.getSources().stream()
                .filter(RssProperties.Source::isEnabled)
                .toList();
        if (enabledSources.isEmpty()) {
            // 没有可用来源时返回空列表，由上层决定是否发送邮件。
            return List.of();
        }

        // 并发数不能超过来源数量，且至少保留一个工作线程。
        int poolSize = Math.max(1, Math.min(properties.getMaxConcurrentSources(), enabledSources.size()));
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        try {
            // 每个来源独立抓取，单个来源失败不会阻断其他来源。
            List<Callable<List<NewsItem>>> tasks = enabledSources.stream()
                    .map(source -> (Callable<List<NewsItem>>) () -> fetchSource(source, cutoff))
                    .toList();
            List<Future<List<NewsItem>>> futures;
            try {
                // 对所有来源设置统一的整体等待上限，防止慢来源拖延整项任务。
                futures = executor.invokeAll(tasks, Math.max(1, properties.getFetchTimeoutSeconds()),
                        TimeUnit.SECONDS);
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                log.error("RSS 并发抓取被中断", exception);
                throw new IllegalStateException("RSS 并发抓取被中断", exception);
            }
            for (int index = 0; index < futures.size(); index++) {
                Future<List<NewsItem>> future = futures.get(index);
                RssProperties.Source source = enabledSources.get(index);
                if (future.isCancelled()) {
                    // 超过整体时限的来源已被取消，只记录并继续汇总已完成结果。
                    log.warn("RSS 来源抓取超出整体超时，来源ID={}，整体超时={}秒", source.getId(),
                            properties.getFetchTimeoutSeconds());
                    continue;
                }
                try {
                    // 按来源顺序收集成功结果，便于日志与配置来源对应。
                    items.addAll(future.get());
                }
                catch (InterruptedException exception) {
                    // 恢复线程中断标记，并终止当前汇总流程。
                    Thread.currentThread().interrupt();
                    log.error("读取 RSS 来源结果被中断，来源ID={}", source.getId(), exception);
                    throw new IllegalStateException("读取 RSS 来源结果被中断", exception);
                }
                catch (ExecutionException exception) {
                    // 隔离单个来源异常，允许其他来源的新闻继续参与筛选。
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    log.warn("RSS 来源抓取失败，来源ID={}，原因={}", source.getId(), cause.getMessage());
                }
            }
        }
        finally {
            // 任务结束后立即停止线程池，避免工作线程泄漏到下一次执行。
            executor.shutdownNow();
        }
        // 先去除重复链接和重复标题，再进行区域配额选择。
        List<NewsItem> uniqueItems = NewsDuplicateUtil.deduplicate(items, Integer.MAX_VALUE);
        // 按配置的国内/国际目标比例选择新闻，实际数量不超过配置上限。
        List<NewsItem> selectedItems = NewsDuplicateUtil.selectByRegion(uniqueItems, properties.getMaxItems(),
                properties.getDomesticRatio());
        long domesticCount = selectedItems.stream()
                .filter(item -> "国内".equals(item.getRegion()))
                .count();
        if (selectedItems.size() < properties.getMaxItems()) {
            log.warn("RSS 可用新闻少于配置上限，时间窗口={}小时，去重后={}条，配置上限={}条，实际选择={}条；不会使用时间窗口外新闻或虚构内容补齐",
                    properties.getWindowHours(), uniqueItems.size(), properties.getMaxItems(), selectedItems.size());
        }
        log.info("RSS 新闻筛选完成，时间窗口={}小时，去重后={}条，实际选择={}条，国内={}条，国际={}条",
                properties.getWindowHours(), uniqueItems.size(), selectedItems.size(), domesticCount,
                selectedItems.size() - domesticCount);
        return selectedItems;
    }

    /**
     * 抓取并解析单个 RSS 来源。
     *
     * @param source RSS 来源配置
     * @param cutoff 新闻发布时间的最早允许时间
     * @return 当前来源在时间窗口内的新闻
     * @throws IOException HTTP 请求或响应读取失败时抛出
     * @throws InterruptedException HTTP 请求线程被中断时抛出
     * @throws FeedException RSS/Atom 内容解析失败时抛出
     */
    List<NewsItem> fetchSource(RssProperties.Source source, Instant cutoff) throws IOException, InterruptedException,
            FeedException {
        // 先解析并限制协议，防止 RSS 配置指向非 HTTP 资源。
        URI uri = URI.create(source.getUrl());
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("RSS 链接必须使用 HTTP 或 HTTPS 协议");
        }
        // 为单个来源设置请求超时、内容类型和识别用的客户端标识。
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.1")
                .header("User-Agent", properties.getUserAgent())
                .GET()
                .build();
        // 读取字节数组以便在解析前检查响应体大小，限制异常大响应的内存占用。
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // 非 2xx 响应不视为有效订阅内容，交由上层按来源隔离处理。
            throw new IOException("HTTP 响应状态码 " + response.statusCode());
        }
        if (response.body().length > properties.getMaxFeedBytes()) {
            // 超过大小上限的订阅直接拒绝解析，避免异常内容消耗资源。
            throw new IOException("RSS 响应超过配置的大小限制");
        }
        try (InputStream inputStream = new ByteArrayInputStream(response.body())) {
            // 使用 Rome 解析 RSS/Atom，并逐条转换、过滤和按发布时间倒序排列。
            SyndFeed feed = new SyndFeedInput().build(new InputSource(inputStream));
            return feed.getEntries().stream()
                    .map(entry -> toNewsItem(source, entry))
                    .filter(Objects::nonNull)
                    .filter(item -> !item.getPublishedAt().isBefore(cutoff))
                    .sorted(Comparator.comparing(NewsItem::getPublishedAt).reversed())
                    .toList();
        }
    }

    /**
     * 将 Rome 解析出的订阅条目转换为原始新闻对象。
     *
     * @param source 新闻来源配置
     * @param entry Rome 订阅条目
     * @return 合法新闻对象；标题、链接或发布时间不合法时返回 null
     */
    private NewsItem toNewsItem(RssProperties.Source source, SyndEntry entry) {
        // 先清洗标题和链接，并兼容发布时间缺失时使用更新时间。
        String title = cleanText(entry.getTitle());
        String link = StringUtils.hasText(entry.getLink()) ? entry.getLink().trim() : "";
        Instant publishedAt = publicationTime(entry);
        if (!StringUtils.hasText(title) || !isHttpLink(link) || publishedAt == null) {
            // 标题、HTTP(S) 链接或发布时间缺失的条目无法可靠展示，直接丢弃。
            return null;
        }
        // 从描述和正文中选择有效内容，并按配置截断以控制后续 AI 输入长度。
        String content = cleanText(contentOf(entry));
        if (content.length() > properties.getMaxContentChars()) {
            // 只保留前 N 个字符，避免超长订阅正文消耗过多模型 Token。
            content = content.substring(0, properties.getMaxContentChars());
        }
        // 保留来源、地区和发布时间等 RSS 元数据，供去重、筛选和邮件展示使用。
        return new NewsItem(source.getId(), source.getName(), title, link, publishedAt, content, source.getRegion());
    }

    /**
     * 获取订阅条目的发布时间；没有发布时间时使用更新时间。
     *
     * @param entry Rome 订阅条目
     * @return 新闻发布时间，无法取得时返回 null
     */
    private Instant publicationTime(SyndEntry entry) {
        // 优先使用正式发布时间；缺失时退回订阅条目的更新时间。
        Date date = entry.getPublishedDate() != null ? entry.getPublishedDate() : entry.getUpdatedDate();
        return date == null ? null : date.toInstant();
    }

    /**
     * 从描述和正文候选内容中选择清洗后最长的一段作为新闻正文。
     *
     * @param entry Rome 订阅条目
     * @return 清洗后的正文内容
     */
    private String contentOf(SyndEntry entry) {
        List<String> candidates = new ArrayList<>();
        if (entry.getDescription() != null) {
            // 将摘要描述加入候选正文。
            candidates.add(entry.getDescription().getValue());
        }
        if (entry.getContents() != null) {
            // 同时收集 Atom/RSS 可能提供的多个正文片段。
            candidates.addAll(entry.getContents().stream()
                   .map(SyndContent::getValue)
                    .toList());
        }
        // 清洗后优先采用信息量更大的最长候选内容。
        return candidates.stream()
                .filter(StringUtils::hasText)
                .map(this::cleanText)
                .max(Comparator.comparingInt(String::length))
                .orElse("");
    }

    /**
     * 删除 HTML 标签、替换不间断空格并合并连续空白。
     *
     * @param value 原始订阅文本
     * @return 适合摘要处理的纯文本
     */
    private String cleanText(String value) {
        if (!StringUtils.hasText(value)) {
            // 空描述统一返回空字符串，避免下游继续处理无效内容。
            return "";
        }
        // Jsoup 去除 HTML 标签，再统一空白字符以便摘要和邮件展示。
        String text = Jsoup.parse(value).text();
        return text.replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * 判断链接是否为 HTTP 或 HTTPS 地址。
     *
     * @param link 待检查链接
     * @return 链接协议合法时返回 true
     */
    private boolean isHttpLink(String link) {
        try {
            // URI.create 同时校验基本格式，再限制为可访问网页的 HTTP(S) 协议。
            URI uri = URI.create(link);
            return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
        }
        catch (IllegalArgumentException exception) {
            // 非法 URI 视为不可用链接，不让单条脏数据中断来源处理。
            return false;
        }
    }
}
