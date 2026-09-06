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

@Component
@Slf4j
public class RssFetcherClient {

    private final HttpClient httpClient;
    private final RssProperties properties;

    @Autowired
    public RssFetcherClient(RssProperties properties) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    RssFetcherClient(RssProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    public List<NewsItem> fetchRecent(Instant now) {
        Instant cutoff = now.minus(Duration.ofHours(properties.getWindowHours()));
        List<NewsItem> items = new ArrayList<>();
        List<RssProperties.Source> enabledSources = properties.getSources().stream()
                .filter(RssProperties.Source::isEnabled)
                .toList();
        if (enabledSources.isEmpty()) {
            return List.of();
        }

        int poolSize = Math.max(1, Math.min(properties.getMaxConcurrentSources(), enabledSources.size()));
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        try {
            List<Callable<List<NewsItem>>> tasks = enabledSources.stream()
                    .map(source -> (Callable<List<NewsItem>>) () -> fetchSource(source, cutoff))
                    .toList();
            List<Future<List<NewsItem>>> futures;
            try {
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
                    log.warn("RSS 来源抓取超出整体超时，来源ID={}，整体超时={}秒", source.getId(),
                            properties.getFetchTimeoutSeconds());
                    continue;
                }
                try {
                    items.addAll(future.get());
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    log.error("读取 RSS 来源结果被中断，来源ID={}", source.getId(), exception);
                    throw new IllegalStateException("读取 RSS 来源结果被中断", exception);
                }
                catch (ExecutionException exception) {
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    log.warn("RSS 来源抓取失败，来源ID={}，原因={}", source.getId(), cause.getMessage());
                }
            }
        }
        finally {
            executor.shutdownNow();
        }
        List<NewsItem> uniqueItems = NewsDuplicateUtil.deduplicate(items, Integer.MAX_VALUE);
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

    List<NewsItem> fetchSource(RssProperties.Source source, Instant cutoff) throws IOException, InterruptedException,
            FeedException {
        URI uri = URI.create(source.getUrl());
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("RSS 链接必须使用 HTTP 或 HTTPS 协议");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.1")
                .header("User-Agent", properties.getUserAgent())
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP 响应状态码 " + response.statusCode());
        }
        if (response.body().length > properties.getMaxFeedBytes()) {
            throw new IOException("RSS 响应超过配置的大小限制");
        }
        try (InputStream inputStream = new ByteArrayInputStream(response.body())) {
            SyndFeed feed = new SyndFeedInput().build(new InputSource(inputStream));
            return feed.getEntries().stream()
                    .map(entry -> toNewsItem(source, entry))
                    .filter(Objects::nonNull)
                    .filter(item -> !item.getPublishedAt().isBefore(cutoff))
                    .sorted(Comparator.comparing(NewsItem::getPublishedAt).reversed())
                    .toList();
        }
    }

    private NewsItem toNewsItem(RssProperties.Source source, SyndEntry entry) {
        String title = cleanText(entry.getTitle());
        String link = StringUtils.hasText(entry.getLink()) ? entry.getLink().trim() : "";
        Instant publishedAt = publicationTime(entry);
        if (!StringUtils.hasText(title) || !isHttpLink(link) || publishedAt == null) {
            return null;
        }
        String content = cleanText(contentOf(entry));
        if (content.length() > properties.getMaxContentChars()) {
            content = content.substring(0, properties.getMaxContentChars());
        }
        return new NewsItem(source.getId(), source.getName(), title, link, publishedAt, content, source.getRegion());
    }

    private Instant publicationTime(SyndEntry entry) {
        Date date = entry.getPublishedDate() != null ? entry.getPublishedDate() : entry.getUpdatedDate();
        return date == null ? null : date.toInstant();
    }

    private String contentOf(SyndEntry entry) {
        List<String> candidates = new ArrayList<>();
        if (entry.getDescription() != null) {
            candidates.add(entry.getDescription().getValue());
        }
        if (entry.getContents() != null) {
            candidates.addAll(entry.getContents().stream()
                   .map(SyndContent::getValue)
                    .toList());
        }
        return candidates.stream()
                .filter(StringUtils::hasText)
                .map(this::cleanText)
                .max(Comparator.comparingInt(String::length))
                .orElse("");
    }

    private String cleanText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String text = Jsoup.parse(value).text();
        return text.replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isHttpLink(String link) {
        try {
            URI uri = URI.create(link);
            return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
        }
        catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
