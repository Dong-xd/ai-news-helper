package com.ai.news.client.ai;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.ai.news.config.AiConfig;
import com.ai.news.model.dto.NewsDigestJson;
import com.ai.news.model.dto.NewsItem;
import com.ai.news.model.dto.NewsJsonItem;
import com.ai.news.model.dto.NewsSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * AI 新闻摘要客户端。
 *
 * <p>该客户端负责组装提示词、调用 Spring AI、校验结构化结果，并在单条新闻失败时使用 RSS 内容降级，
 * 保证一次任务中的其他新闻仍可继续处理。</p>
 */
@Component
@Slf4j
public class AiSummaryClient {

    /**
     * 用户提示词中的新闻标题占位符。
     */
    private static final String NEWS_TITLE_TOKEN = "{news-title}";

    /**
     * 用户提示词中的新闻分类占位符。
     */
    private static final String NEWS_TYPE_TOKEN = "{news-type}";

    /**
     * 用户提示词中的新闻正文占位符。
     */
    private static final String NEWS_CONTENT_TOKEN = "{news-content}";

    /**
     * 用户提示词中的新闻正文占位符。
     */
    private static final String NEWS_PUBLISH_DATE = "{news-publish-date}";

    /**
     * 用于识别文本中网页地址的正则表达式，避免链接污染标题和摘要。
     */
    private static final Pattern URL_TOKEN_PATTERN = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>()]+");

    /**
     * 用于判断文本是否包含中文字符的正则表达式。
     */
    private static final Pattern CHINESE_TEXT_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]");

    /**
     * AI 输出允许使用的板块键名，顺序同时决定邮件板块顺序。
     */
    private static final List<String> BOARD_KEYS = List.of(
            "heavyRelease", "techFrontier", "industryNews", "openSourceTools");

    /**
     * AI 输出板块键名与中文展示名称的映射。
     */
    private static final Map<String, String> BOARD_NAMES = Map.of(
            "heavyRelease", "重磅发布",
            "techFrontier", "技术前沿",
            "industryNews", "行业动态",
            "openSourceTools", "开源工具");

    /**
     * AI 摘要失败时使用的默认板块。
     */
    private static final String DEFAULT_FALLBACK_TYPE = "techFrontier";

    /**
     * 邮件展示时间使用的北京时间时区。
     */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 新闻发布时间的展示格式。
     */
    private static final DateTimeFormatter BEIJING_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Spring AI 聊天客户端，用于调用已配置的大模型。
     */
    @Autowired
    private ChatClient chatClient;

    /**
     * AI 摘要提示词和重试策略配置。
     */
    @Autowired
    private AiConfig config;

    /**
     * 为新闻列表逐条生成并校验结构化摘要。
     *
     * <p>单条新闻调用失败时只对该条使用 RSS 内容降级，不影响其他新闻的摘要生成。</p>
     *
     * @param newsItems 待处理的 RSS 新闻列表
     * @return 结构化摘要及降级状态
     */
    public NewsSummary summarize(List<NewsItem> newsItems) {
        // 先校验调用方传入的新闻列表，避免后续处理出现空指针。
        Objects.requireNonNull(newsItems, "新闻列表不能为空");
        if (newsItems.isEmpty()) {
            // 没有新闻时直接返回空的固定结构，不触发外部 AI 调用。
            return new NewsSummary(buildDigestJson(List.of()), false, "");
        }

        String systemPrompt;
        String userPromptTemplate;
        try {
            // 读取并校验系统提示词和用户提示词模板，确保 AI 请求具备完整上下文。
            systemPrompt = requirePrompt(config.getSystemPrompt(), "news.ai.system-prompt");
            userPromptTemplate = requireUserPromptTemplate(config.getUserPromptTemplate());
        }
        catch (IllegalStateException exception) {
            // 提示词配置错误时整批降级为 RSS 原始内容，避免任务完全中断。
            log.error("AI 摘要配置无效，使用 RSS 内容降级", exception);
            return fallback(newsItems, exception.getMessage());
        }

        // 将重试次数限制为至少一次，并预先分配结果容量以减少扩容。
        int attempts = Math.max(1, config.getMaxAttempts());
        List<NewsJsonItem> itemList = new ArrayList<>(newsItems.size());
        Exception lastFailure = null;
        boolean fallbackUsed = false;
        for (int index = 0; index < newsItems.size(); index++) {
            NewsItem item = newsItems.get(index);
            try {
                // 每条新闻独立调用 AI，成功后合并 AI 字段与 RSS 元数据。
                NewsDigestDto digest = summarizeItem(item, systemPrompt, userPromptTemplate, attempts);
                itemList.add(toJsonItem(item, digest));
            }
            catch (Exception exception) {
                // 单条新闻失败只影响当前条目，其余新闻继续处理。
                lastFailure = exception;
                fallbackUsed = true;
                itemList.add(buildFallbackItem(item));
                log.warn("第 {} 条新闻 AI 摘要失败，使用 RSS 内容降级，原因={}", index + 1,
                        failureMessage(exception));
            }
        }

        if (fallbackUsed) {
            // 记录批处理中发生过降级，便于排查部分 AI 调用失败的原因。
            log.warn("AI 摘要处理完成，部分新闻使用 RSS 内容，原因={}", failureMessage(lastFailure));
        }
        // 将所有条目按板块整理成邮件渲染所需的固定 JSON 结构。
        return new NewsSummary(buildDigestJson(itemList), fallbackUsed,
                fallbackUsed ? failureMessage(lastFailure) : "");
    }

    /**
     * 将 RSS 原始新闻直接转换为结构化兜底摘要，不调用外部 AI 服务。
     *
     * @param newsItems 待转换的 RSS 新闻列表
     * @param reason 降级原因，可为空
     * @return 使用 RSS 内容构建的结构化摘要
     */
    public NewsSummary fallback(List<NewsItem> newsItems, String reason) {
        // 兜底路径同样校验输入，确保后续能够稳定构建摘要结构。
        Objects.requireNonNull(newsItems, "新闻列表不能为空");
        List<NewsJsonItem> fallbackItems = new ArrayList<>(newsItems.size());
        for (int index = 0; index < newsItems.size(); index++) {
            // 只复制 RSS 已提供的字段，不凭空补充新闻事实。
            fallbackItems.add(buildFallbackItem(newsItems.get(index)));
        }
        // 保留降级原因，供上层日志或运行状态使用。
        return new NewsSummary(buildDigestJson(fallbackItems), true, reason == null ? "" : reason);
    }

    /**
     * 调用 AI 处理单条新闻，并按照配置执行有限次数重试。
     *
     * @param item 待处理的原始新闻
     * @param systemPrompt 系统提示词
     * @param userPromptTemplate 用户提示词模板
     * @param attempts 最大尝试次数
     * @return 已通过校验的 AI 摘要
     */
    private NewsDigestDto summarizeItem(NewsItem item, String systemPrompt, String userPromptTemplate, int attempts) {
        // 先把当前新闻填充到模板中，后续重试复用相同的请求内容。
        String userPrompt = buildUserPrompt(userPromptTemplate, item);
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                // 通过 Spring AI 请求结构化对象，避免在业务层手动解析模型文本。
                NewsDigestDto digest = chatClient.prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .call()
                        .entity(NewsDigestDto.class);
                // 只有通过字段约束校验的结果才能进入邮件数据模型。
                return validateDigest(digest);
            }
            catch (Exception exception) {
                // 保存最后一次异常；若仍有剩余次数，则等待后重新调用。
                lastFailure = exception;
                log.warn("AI 摘要第 {} 次尝试失败，原因={}", attempt, failureMessage(exception));
                if (attempt < attempts) {
                    sleepBeforeRetry();
                }
            }
        }
        // 所有尝试均失败后交给上层执行单条 RSS 降级。
        throw new IllegalStateException("AI 摘要生成失败", lastFailure);
    }

    /**
     * 校验并清洗 AI 返回的单条摘要，确保分类、语言和链接约束有效。
     *
     * @param digest AI 返回的摘要对象
     * @return 清洗后的摘要对象
     */
    private NewsDigestDto validateDigest(NewsDigestDto digest) {
        if (digest == null) {
            // 空响应无法提供任何可展示内容，直接判定为无效结果。
            throw new IllegalStateException("AI 返回空的摘要对象");
        }
        // 统一清洗模型字段，避免换行和首尾空白影响后续展示。
        String title = cleanText(digest.title());
        String summary = normalizeLineBreaks(digest.summary());
        String type = digest.type();
        if (!BOARD_KEYS.contains(type)) {
            // 分类必须属于系统约定的四个板块，才能正确分组到邮件中。
            throw new IllegalStateException("AI 新闻分类无效：" + type);
        }
        if (!containsChinese(title) || isPlaceholderTitle(title) || URL_TOKEN_PATTERN.matcher(title).find()) {
            // 标题必须是具体中文标题，且不能把网页地址当作标题返回。
            throw new IllegalStateException("AI 新闻标题必须是具体中文标题，且不能包含网页地址");
        }
        if (!StringUtils.hasText(summary) || !containsChinese(summary) || URL_TOKEN_PATTERN.matcher(summary).find()) {
            // 摘要必须包含真实中文内容，避免空内容或链接污染邮件正文。
            throw new IllegalStateException("AI 新闻摘要必须是非空中文内容，且不能包含网页地址");
        }
        // 返回清洗后的值，保证下游不再重复处理同一字段。
        return new NewsDigestDto(title, summary, type);
    }

    /**
     * 使用原始新闻内容填充用户提示词模板，并移除输入文本中的网页地址。
     *
     * @param template 用户提示词模板
     * @param item 原始新闻
     * @return 完整用户提示词
     */
    private String buildUserPrompt(String template, NewsItem item) {
        // 将新闻标题、允许的板块和正文分别写入模板，并移除输入链接。
        return template
                .replace(NEWS_TITLE_TOKEN, cleanText(removeUrls(item.getTitle())))
                .replace(NEWS_TYPE_TOKEN, String.join("、", BOARD_KEYS))
                .replace(NEWS_CONTENT_TOKEN, cleanText(removeUrls(item.getContent())))
                .replace(NEWS_PUBLISH_DATE, cleanText(removeUrls(
                        item.getPublishedAt() != null ? item.getPublishedAt().toString() : null)));
    }

    /**
     * 将 AI 单条摘要与原始 RSS 元数据合并为邮件使用的结构化条目。
     *
     * @param item 原始 RSS 新闻
     * @param digest AI 摘要
     * @return 结构化新闻条目
     */
    private NewsJsonItem toJsonItem(NewsItem item, NewsDigestDto digest) {
        // AI 只负责标题、摘要和板块，来源、时间、链接继续使用 RSS 原始值。
        return NewsJsonItem.builder()
                .title(digest.title())
                .summary(digest.summary())
                .source(cleanText(item.getSourceName()))
                .publishTime(formatPublishedAt(item))
                .url(item.getLink())
                .type(digest.type())
                .publishedAt(item.getPublishedAt())
                .build();
    }

    /**
     * 根据 RSS 原始字段构建单条兜底新闻，避免凭空生成事实内容。
     *
     * @param item 原始 RSS 新闻
     * @return RSS 内容对应的结构化新闻条目
     */
    private NewsJsonItem buildFallbackItem(NewsItem item) {
        // 清洗 RSS 文本并移除链接，保证兜底内容可直接展示。
        String title = cleanText(removeUrls(item.getTitle()));
        String summary = cleanText(removeUrls(item.getContent()));
        if (!StringUtils.hasText(summary)) {
            // RSS 没有正文时只说明事实不足，不编造额外新闻内容。
            summary = "根据新闻标题“" + title
                    + "”整理，原始资料没有提供更多可核实事实，请查看原文了解详情。";
        }
        // 兜底新闻统一放入技术前沿板块，同时保留 RSS 元数据。
        return NewsJsonItem.builder()
                .title(title)
                .summary(summary)
                .source(cleanText(removeUrls(item.getSourceName())))
                .publishTime(formatPublishedAt(item))
                .url(item.getLink())
                .type(DEFAULT_FALLBACK_TYPE)
                .publishedAt(item.getPublishedAt())
                .build();
    }

    /**
     * 按板块键将新闻条目分组，构建固定结构的 JSON 摘要。
     *
     * @param items 待分组的结构化新闻条目
     * @return 按板块组织的摘要对象
     */
    private NewsDigestJson buildDigestJson(List<NewsJsonItem> items) {
        // 先为每个固定板块创建空列表，确保空板块也出现在 JSON 中。
        Map<String, List<NewsJsonItem>> groupedItems = new LinkedHashMap<>();
        for (String boardKey : BOARD_KEYS) {
            groupedItems.put(boardKey, new ArrayList<>());
        }
        for (NewsJsonItem item : items) {
            // 按条目分类键归组，未知分类不能进入邮件数据结构。
            List<NewsJsonItem> boardItems = groupedItems.get(item.getType());
            if (boardItems == null) {
                throw new IllegalStateException("新闻分类不受支持：" + item.getType());
            }
            boardItems.add(item);
        }
        // 按固定字段顺序组装最终摘要，保证模板绑定稳定。
        return NewsDigestJson.builder()
                .heavyRelease(groupedItems.get("heavyRelease"))
                .techFrontier(groupedItems.get("techFrontier"))
                .industryNews(groupedItems.get("industryNews"))
                .openSourceTools(groupedItems.get("openSourceTools"))
                .build();
    }

    /**
     * 判断文本是否包含至少一个中文字符。
     *
     * @param value 待判断文本
     * @return 包含中文且文本非空时返回 true
     */
    private boolean containsChinese(String value) {
        // 非空且至少包含一个中文字符才视为中文内容。
        return StringUtils.hasText(value) && CHINESE_TEXT_PATTERN.matcher(value).find();
    }

    /**
     * 判断标题是否为泛化占位标题，而不是具体新闻标题。
     *
     * @param title 待判断标题
     * @return 属于占位标题时返回 true
     */
    private boolean isPlaceholderTitle(String title) {
        // 去除空白后比较，识别模型返回的泛化标题或板块名称。
        String normalized = title.replaceAll("\\s+", "");
        return "AI新闻动态".equals(normalized) || BOARD_NAMES.containsValue(normalized);
    }

    /**
     * 校验必填提示词配置。
     *
     * @param prompt 待校验提示词
     * @param propertyName 配置项名称
     * @return 原始提示词
     */
    private String requirePrompt(String prompt, String propertyName) {
        if (!StringUtils.hasText(prompt)) {
            // 空提示词会导致 AI 请求缺少约束，因此在调用前显式失败。
            throw new IllegalStateException("未配置 " + propertyName);
        }
        return prompt;
    }

    /**
     * 校验用户提示词模板及其必需占位符。
     *
     * @param prompt 用户提示词模板
     * @return 校验通过的提示词模板
     */
    private String requireUserPromptTemplate(String prompt) {
        // 先校验模板本身非空，再逐项确认新闻数据占位符完整。
        String template = requirePrompt(prompt, "news.ai.user-prompt-template");
        for (String token : List.of(NEWS_TITLE_TOKEN, NEWS_TYPE_TOKEN, NEWS_CONTENT_TOKEN)) {
            if (!template.contains(token)) {
                // 缺少任一占位符都会使对应新闻信息无法传给模型。
                throw new IllegalStateException("AI 用户提示词缺少 " + token + " 占位符");
            }
        }
        return template;
    }

    /**
     * 将不同平台的换行符统一为 Unix 换行并去除首尾空白。
     *
     * @param value 待处理文本
     * @return 统一换行后的文本
     */
    private String normalizeLineBreaks(String value) {
        // 将 Windows 和旧式 Mac 换行统一，便于邮件模板保持一致排版。
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    /**
     * 将原始发布时间转换为北京时间的小时和分钟。
     *
     * @param item 原始新闻
     * @return HH:mm 格式的北京时间
     */
    private String formatPublishedAt(NewsItem item) {
        // 原始时间统一转换为北京时间，只展示小时和分钟。
        return item.getPublishedAt().atZone(DISPLAY_ZONE).format(BEIJING_TIME_FORMATTER);
    }

    /**
     * 将文本中的网页地址替换为提示性占位文本。
     *
     * @param value 待清洗文本
     * @return 移除网页地址后的文本
     */
    private String removeUrls(String value) {
        if (value == null) {
            // 空输入统一转换为空字符串，避免正则处理空引用。
            return "";
        }
        // 用提示文本替换链接，保留语句位置但不把网页地址送入摘要内容。
        return URL_TOKEN_PATTERN.matcher(value).replaceAll("[链接已省略]");
    }

    /**
     * 合并连续空白并去除文本首尾空白。
     *
     * @param value 待清洗文本
     * @return 清洗后的文本
     */
    private String cleanText(String value) {
        // 合并连续空白并去除首尾空白，得到稳定的单行展示文本。
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    /**
     * 按配置等待下一次 AI 调用，并在中断时恢复线程中断标记。
     */
    private void sleepBeforeRetry() {
        try {
            // 根据配置等待，避免连续失败时立即高频请求模型。
            Thread.sleep(Math.max(0, config.getRetryBackoffMillis()));
        }
        catch (InterruptedException exception) {
            // 恢复中断标记，让上层能够感知线程已被取消。
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 重试过程被中断", exception);
        }
    }

    /**
     * 提取异常的可读原因，避免日志中出现空原因。
     *
     * @param exception 摘要异常
     * @return 异常消息或异常类型名称
     */
    private String failureMessage(Exception exception) {
        if (exception == null) {
            // 没有异常对象时提供统一文本，避免日志原因为空。
            return "未知错误";
        }
        // 优先使用异常消息，没有消息时退回异常类型名称。
        return StringUtils.hasText(exception.getMessage())
                ? exception.getMessage()
                : exception.getClass().getSimpleName();
    }

    /**
     * AI 单条新闻摘要的内部传输对象。
     *
     * @param title AI 生成的中文标题
     * @param summary AI 生成的中文摘要
     * @param type AI 选择的板块键名
     */
    private record NewsDigestDto(String title, String summary, String type) {
    }
}
