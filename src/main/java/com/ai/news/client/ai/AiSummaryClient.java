package com.ai.news.client.ai;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ai.news.config.AiConfig;
import com.ai.news.model.dto.NewsItem;
import com.ai.news.model.dto.NewsSummary;
import com.ai.news.util.NewsMarkdownParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Slf4j
public class AiSummaryClient {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[\\[(N\\d{2})]]");
    private static final Pattern URL_TOKEN_PATTERN = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>()]+");
    private static final Pattern CHINESE_TEXT_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]");

    @Autowired
    private ChatClient chatClient;
    @Autowired
    private AiConfig config;
    private final NewsMarkdownParser markdownParser = new NewsMarkdownParser();

    public NewsSummary summarize(List<NewsItem> newsItems) {
        String userPrompt = resolvePrompt(config.getUserPromptTemplate())
                .replace("{news-items}", buildNewsInput(newsItems));
        int attempts = Math.max(1, config.getMaxAttempts());
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String markdown = chatClient.prompt()
                        .system(resolvePrompt(config.getSystemPrompt()))
                        .user(userPrompt)
                        .call()
                        .content();
                validate(markdown, newsItems);
                return new NewsSummary(sortByImportance(markdown.trim(), newsItems), false, "");
            }
            catch (Exception exception) {
                lastFailure = exception;
                log.warn("AI 摘要第 {} 次尝试失败，原因={}", attempt, exception.getMessage());
                if (attempt < attempts) {
                    sleepBeforeRetry();
                }
            }
        }
        String reason = lastFailure == null ? "未知错误" : lastFailure.getMessage();
        log.error("AI 摘要在 {} 次尝试后仍失败，降级使用 RSS 内容，原因={}", attempts, reason);
        return fallback(newsItems, reason);
    }

    public NewsSummary fallback(List<NewsItem> newsItems, String reason) {
        StringBuilder markdown = new StringBuilder("# 今日概览\n\n")
                .append("AI 摘要暂不可用，本期展示 RSS 抓取到的原始新闻信息。\n\n");
        for (String boardName : NewsMarkdownParser.BOARD_NAMES) {
            markdown.append("## ").append(boardName).append("\n\n");
            if (!"技术前沿".equals(boardName)) {
                continue;
            }
            for (int index = 0; index < newsItems.size(); index++) {
                NewsItem newsItem = newsItems.get(index);
                String citation = citation(index);
                String content = removeUrls(newsItem.getContent());
                if (!StringUtils.hasText(content)) {
                    content = "RSS 未提供摘要内容，暂无法补充更多事实。";
                }
                content = limit(content, Math.max(1, config.getMaximumSummaryChars()));
                markdown.append("### ")
                        .append(escapeMarkdown(removeUrls(newsItem.getTitle())))
                        .append(" [[").append(citation).append("]]\n")
                        .append("- 来源：").append(escapeMarkdown(removeUrls(newsItem.getSourceName()))).append('\n')
                        .append("- 发布时间：").append(newsItem.getPublishedAt()).append('\n')
                        .append("- 分类：RSS 原始信息\n")
                        .append("- 重要性：中\n")
                        .append("- 摘要：").append(escapeMarkdown(content)).append('\n')
                        .append("- 关注理由：AI 摘要暂不可用，仅展示 RSS 提供的信息。\n\n");
            }
        }
        return new NewsSummary(markdown.toString().trim(), true, reason == null ? "" : reason);
    }

    private void validate(String markdown, List<NewsItem> newsItems) {
        if (!StringUtils.hasText(markdown)) {
            throw new IllegalStateException("AI 返回空的 Markdown 内容");
        }
        if (URL_TOKEN_PATTERN.matcher(markdown).find()) {
            throw new IllegalStateException("AI 输出包含未授权的网页地址");
        }
        if (markdown.length() > config.getMaxOutputChars()) {
            throw new IllegalStateException("AI Markdown 超过配置的输出长度限制");
        }

        NewsMarkdownParser.ParsedDocument document = markdownParser.parse(markdown);
        if (!StringUtils.hasText(document.overview())) {
            throw new IllegalStateException("AI 输出缺少今日概览内容");
        }
        if (document.sections().size() != NewsMarkdownParser.BOARD_NAMES.size()) {
            throw new IllegalStateException("AI 输出必须包含四个固定新闻板块");
        }
        for (int index = 0; index < NewsMarkdownParser.BOARD_NAMES.size(); index++) {
            if (!NewsMarkdownParser.BOARD_NAMES.get(index).equals(document.sections().get(index).name())) {
                throw new IllegalStateException("AI 新闻板块顺序不符合规范");
            }
        }

        Map<String, NewsItem> itemsByCitation = new HashMap<>();
        for (int index = 0; index < newsItems.size(); index++) {
            itemsByCitation.put(citation(index), newsItems.get(index));
        }
        Map<String, Integer> citationCounts = new HashMap<>();
        Matcher citationMatcher = CITATION_PATTERN.matcher(markdown);
        while (citationMatcher.find()) {
            String citation = citationMatcher.group(1);
            if (!itemsByCitation.containsKey(citation)) {
                throw new IllegalStateException("AI 返回了未知的引用标记：" + citation);
            }
            citationCounts.merge(citation, 1, Integer::sum);
        }

        int articleCount = 0;
        Set<String> attentionReasons = new HashSet<>();
        for (NewsMarkdownParser.ParsedSection section : document.sections()) {
            for (NewsMarkdownParser.ParsedArticle article : section.articles()) {
                articleCount++;
                if (articleCount > newsItems.size()) {
                    throw new IllegalStateException("AI 输出新闻数量超过 RSS 输入数量");
                }
                if (!itemsByCitation.containsKey(article.citation())
                        || citationCounts.getOrDefault(article.citation(), 0) != 1) {
                    throw new IllegalStateException("AI 新闻引用标记无效或重复：" + article.citation());
                }
                validateArticle(article, attentionReasons);
            }
        }
        if (articleCount == 0) {
            throw new IllegalStateException("AI 输出没有符合要求的新闻");
        }
    }

    private void validateArticle(NewsMarkdownParser.ParsedArticle article, Set<String> attentionReasons) {
        if (!containsChinese(article.title()) || isPlaceholderTitle(article.title())) {
            throw new IllegalStateException("AI 新闻标题必须由模型翻译为具体中文标题");
        }
        for (String fieldName : NewsMarkdownParser.FIELD_NAMES) {
            if (!StringUtils.hasText(article.field(fieldName))) {
                throw new IllegalStateException("AI 输出缺少新闻详情字段：" + fieldName);
            }
        }
        String category = article.field("分类");
        if (!containsChinese(category) || isGenericCategory(category)) {
            throw new IllegalStateException("AI 新闻分类必须根据新闻内容动态生成");
        }
        String importance = article.field("重要性");
        if (!List.of("高", "中", "低").contains(importance)) {
            throw new IllegalStateException("AI 新闻重要性只能是高、中或低");
        }
        String summary = article.field("摘要");
        int minimumChars = Math.max(0, config.getMinimumSummaryChars());
        int maximumChars = Math.max(minimumChars, config.getMaximumSummaryChars());
        if (summary.length() < minimumChars || summary.length() > maximumChars) {
            throw new IllegalStateException("AI 新闻摘要长度必须在 " + minimumChars + " 至 "
                    + maximumChars + " 个字符之间");
        }
        String attentionReason = article.field("关注理由");
        if (normalizeForComparison(summary).equals(normalizeForComparison(attentionReason))) {
            throw new IllegalStateException("AI 新闻摘要和关注理由不能重复");
        }
        if (!attentionReasons.add(normalizeForComparison(attentionReason))) {
            throw new IllegalStateException("不同新闻必须生成不同的关注理由");
        }
    }

    private String sortByImportance(String markdown, List<NewsItem> newsItems) {
        NewsMarkdownParser.ParsedDocument document = markdownParser.parse(markdown);
        Map<String, Instant> publishedAtByCitation = new HashMap<>();
        for (int index = 0; index < newsItems.size(); index++) {
            publishedAtByCitation.put(citation(index), newsItems.get(index).getPublishedAt());
        }
        Comparator<NewsMarkdownParser.ParsedArticle> comparator = Comparator
                .comparingInt((NewsMarkdownParser.ParsedArticle article) -> importanceRank(article.field("重要性")))
                .thenComparing(article -> publishedAtByCitation.getOrDefault(article.citation(), Instant.MIN),
                        Comparator.reverseOrder())
                .thenComparing(NewsMarkdownParser.ParsedArticle::citation);
        return markdownParser.sortArticles(document, comparator).toMarkdown();
    }

    private int importanceRank(String importance) {
        if (importance.startsWith("高")) {
            return 0;
        }
        if (importance.startsWith("中")) {
            return 1;
        }
        return 2;
    }

    private boolean containsChinese(String value) {
        return StringUtils.hasText(value) && CHINESE_TEXT_PATTERN.matcher(value).find();
    }

    private String buildNewsInput(List<NewsItem> newsItems) {
        StringBuilder input = new StringBuilder();
        int maxLength = Math.max(1000, config.getMaxInputChars());
        for (int index = 0; index < newsItems.size(); index++) {
            NewsItem item = newsItems.get(index);
            String prefix = "[" + citation(index) + "]\n"
                    + "标题：" + item.getTitle() + "\n"
                    + "来源：" + item.getSourceName() + "\n"
                    + "区域：" + item.getRegion() + "\n"
                    + "发布时间：" + item.getPublishedAt() + "\n"
                    + "RSS 内容：";
            String suffix = "\n\n";
            int remainingItems = newsItems.size() - index;
            int perItemLimit = Math.max(1, (maxLength - input.length()) / remainingItems);
            int contentLimit = Math.max(0, perItemLimit - prefix.length() - suffix.length());
            String sanitizedContent = removeUrls(item.getContent());
            String content = sanitizedContent.substring(0, Math.min(sanitizedContent.length(), contentLimit));
            String block = prefix + content + suffix;
            if (input.length() + block.length() > maxLength) {
                block = block.substring(0, Math.max(0, maxLength - input.length()));
            }
            input.append(block);
        }
        return input.toString();
    }

    private String resolvePrompt(String prompt) {
        if (prompt == null) {
            return "";
        }
        return prompt
                .replace("{minimum-summary-chars}", String.valueOf(config.getMinimumSummaryChars()))
                .replace("{maximum-summary-chars}", String.valueOf(config.getMaximumSummaryChars()))
                .replace("__MIN__", String.valueOf(config.getMinimumSummaryChars()));
    }

    private String citation(int index) {
        return "N" + String.format("%02d", index + 1);
    }

    private String removeUrls(String value) {
        if (value == null) {
            return "";
        }
        return URL_TOKEN_PATTERN.matcher(value).replaceAll("[链接已省略]");
    }

    private String escapeMarkdown(String value) {
        return value.replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    private String limit(String value, int maximumChars) {
        return value.substring(0, Math.min(value.length(), maximumChars));
    }

    private boolean isPlaceholderTitle(String title) {
        String normalized = title.replaceAll("\\s+", "");
        return "国际AI新闻动态".equals(normalized)
                || "AI新闻动态".equals(normalized)
                || NewsMarkdownParser.BOARD_NAMES.contains(normalized);
    }

    private boolean isGenericCategory(String category) {
        String normalized = category.replaceAll("\\s+", "");
        return "AI新闻".equals(normalized) || "人工智能新闻".equals(normalized);
    }

    private String normalizeForComparison(String value) {
        return value.replaceAll("\\s+", "").trim();
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(Math.max(0, config.getRetryBackoffMillis()));
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 重试过程被中断", exception);
        }
    }
}
