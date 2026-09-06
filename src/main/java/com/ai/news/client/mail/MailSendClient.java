package com.ai.news.client.mail;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.ai.news.config.MailConfig;
import com.ai.news.model.dto.NewsDigest;
import com.ai.news.model.dto.NewsItem;
import com.ai.news.util.NewsMarkdownParser;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Component
@Slf4j
public class MailSendClient {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm");
    private static final DateTimeFormatter DATE_WITH_WEEKDAY_FORMATTER = DateTimeFormatter.ofPattern(
            "yyyy年MM月dd日 EEEE", Locale.SIMPLIFIED_CHINESE);
    private static final Pattern OVERVIEW_HEADING_PATTERN = Pattern.compile(
            "(?m)^#\\s+今日概览\\s*[：:]?\\s*$");
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[\\[N\\d{2}]]");
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Map<String, String> BOARD_DISPLAY_NAMES = Map.of(
            "重磅发布", "🚀 重磅发布",
            "技术前沿", "🔬 技术前沿",
            "行业动态", "📊 行业动态",
            "开源工具", "🧰 开源工具");

    @Autowired
    private JavaMailSender mailSender;
    @Autowired
    private TemplateEngine templateEngine;
    @Autowired
    private MailConfig config;
    private final NewsMarkdownParser markdownParser = new NewsMarkdownParser();

    public void send(NewsDigest digest) {
        if (!config.isEnabled()) {
            log.info("邮件发送已禁用");
            return;
        }
        validateMailConfiguration();
        RenderedMail renderedMail = render(digest);
        int attempts = Math.max(1, config.getSendRetryAttempts());
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                sendOnce(renderedMail, digest);
                log.info("AI 新闻邮件发送成功，日期={}，新闻数量={}", digest.getDate(),
                        renderedMail.view().articleCount());
                return;
            }
            catch (MailException | IllegalStateException exception) {
                lastFailure = exception;
                log.warn("邮件第 {} 次发送尝试失败，原因={}", attempt, exception.getMessage());
                if (attempt < attempts) {
                    sleepBeforeRetry();
                }
            }
        }
        throw lastFailure == null ? new IllegalStateException("邮件发送失败") : lastFailure;
    }

    private void sendOnce(RenderedMail renderedMail, NewsDigest digest) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(config.getFrom(), config.getFromName());
            helper.setTo(config.getTo());
            helper.setSubject(config.getSubjectPrefix() + " - " + digest.getDate().format(DATE_FORMATTER));
            helper.setText(renderedMail.plainText(), renderedMail.html());
            mailSender.send(message);
        }
        catch (Exception exception) {
            throw new IllegalStateException("无法创建或发送 MIME 邮件", exception);
        }
    }

    private RenderedMail render(NewsDigest digest) {
        EmailView view = buildEmailView(digest);
        Context context = new Context(Locale.SIMPLIFIED_CHINESE, Map.of("mail", view));
        String html = templateEngine.process("news-email", context);
        return new RenderedMail(html, toPlainText(view), view);
    }

    private EmailView buildEmailView(NewsDigest digest) {
        NewsMarkdownParser.ParsedDocument document = markdownParser.parse(
                removeGeneratedIntroduction(digest.getMarkdown()));
        Map<String, NewsItem> itemsByCitation = new HashMap<>();
        for (int index = 0; index < digest.getNewsItems().size(); index++) {
            itemsByCitation.put(citation(index), digest.getNewsItems().get(index));
        }

        Set<String> trustedDomains = trustedLinkDomains();
        List<EmailSection> sections = new ArrayList<>();
        int articleCount = 0;
        for (NewsMarkdownParser.ParsedSection section : document.sections()) {
            List<EmailItem> items = new ArrayList<>();
            for (NewsMarkdownParser.ParsedArticle article : section.articles()) {
                NewsItem sourceItem = itemsByCitation.get(article.citation());
                if (sourceItem == null) {
                    throw new IllegalStateException("邮件新闻缺少引用对应的 RSS 数据：" + article.citation());
                }
                String summary = displayText(article.field("摘要"));
                if (!StringUtils.hasText(summary)) {
                    summary = displayText(sourceItem.getContent());
                }
                items.add(new EmailItem(
                        displayText(article.title()),
                        summary,
                        sourceItem.getSourceName(),
                        sourceItem.getPublishedAt().atZone(DISPLAY_ZONE).format(DATE_TIME_FORMATTER),
                        safeOriginalLink(sourceItem, trustedDomains)));
                articleCount++;
            }
            sections.add(new EmailSection(
                    BOARD_DISPLAY_NAMES.getOrDefault(section.name(), section.name()), items));
        }
        if (articleCount == 0) {
            throw new IllegalStateException("邮件没有可展示的新闻内容");
        }

        String overview = displayText(document.overview());
        if (!StringUtils.hasText(overview)) {
            overview = buildOverview(sections);
        }
        int estimatedReadMinutes = Math.max(1, (int) Math.ceil(articleCount / 4.0));
        String dateText = digest.getDate().format(DATE_WITH_WEEKDAY_FORMATTER);
        return new EmailView(config.getBrandName(), dateText, estimatedReadMinutes, overview, sections, articleCount);
    }

    /**
     * 模型偶尔会在正式摘要前复述任务要求，邮件只保留从“今日概览”开始的正文。
     */
    private String removeGeneratedIntroduction(String markdown) {
        String normalized = markdown == null ? "" : markdown.trim();
        java.util.regex.Matcher matcher = OVERVIEW_HEADING_PATTERN.matcher(normalized);
        return matcher.find() ? normalized.substring(matcher.start()).trim() : normalized;
    }

    private String buildOverview(List<EmailSection> sections) {
        int articleCount = sections.stream().mapToInt(section -> section.getItems().size()).sum();
        String categoryText = sections.stream()
                .filter(section -> !section.getItems().isEmpty())
                .map(section -> section.getDisplayName() + "（" + section.getItems().size() + " 条）")
                .collect(Collectors.joining("、"));
        return "共收录 " + articleCount + " 条新闻，覆盖" + categoryText + "。本期内容已按新闻重要性和发布时间整理。";
    }

    private String toPlainText(EmailView view) {
        StringBuilder text = new StringBuilder()
                .append(view.getBrandName()).append(" | ").append(view.getDateText())
                .append(" · 预计阅读").append(view.getEstimatedReadMinutes()).append("分钟\n\n")
                .append("今日速览：").append(view.getOverview()).append('\n');
        for (EmailSection section : view.getSections()) {
            if (section.getItems().isEmpty()) {
                continue;
            }
            text.append('\n').append(section.getDisplayName()).append('\n');
            for (EmailItem item : section.getItems()) {
                text.append('\n').append(item.getTitle());
                if (StringUtils.hasText(item.getOriginalLink())) {
                    text.append(" (").append(item.getOriginalLink()).append(')');
                }
                text.append("\n摘要：").append(item.getSummary())
                        .append("\n来源：").append(item.getSourceName())
                        .append(" · ").append(item.getPublishedAtText()).append('\n');
            }
        }
        return text.append('\n')
                .append("由 ").append(view.getBrandName())
                .append(" 每日定时推送 · 点击标题跳转原文 · 内容由 AI 整理，仅供参考")
                .toString()
                .trim();
    }

    private String displayText(String value) {
        if (value == null) {
            return "";
        }
        return CITATION_PATTERN.matcher(value)
                .replaceAll("")
                .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1")
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .replace("\\*", "*")
                .replace("\\_", "_")
                .replace("\\[", "[")
                .replace("\\]", "]")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private Set<String> trustedLinkDomains() {
        if (!StringUtils.hasText(config.getTrustedLinkDomains())) {
            return Set.of();
        }
        return Arrays.stream(config.getTrustedLinkDomains().split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(domain -> domain.toLowerCase(Locale.ROOT))
                .map(domain -> domain.startsWith(".") ? domain.substring(1) : domain)
                .map(domain -> domain.endsWith(".") ? domain.substring(0, domain.length() - 1) : domain)
                .filter(domain -> !domain.contains(":") && !domain.contains("/"))
                .collect(Collectors.toUnmodifiableSet());
    }

    private String safeOriginalLink(NewsItem item, Set<String> trustedDomains) {
        if (!config.isShowOriginalLinks() || !StringUtils.hasText(item.getLink())) {
            return "";
        }
        try {
            URI uri = new URI(item.getLink().trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || !StringUtils.hasText(host) || uri.getUserInfo() != null
                    || (uri.getPort() >= 0 && uri.getPort() > 65535)
                    || (!trustedDomains.isEmpty() && !isTrustedHost(host, trustedDomains))) {
                return "";
            }
            StringBuilder safeUrl = new StringBuilder()
                    .append(scheme.toLowerCase(Locale.ROOT)).append("://")
                    .append(host.toLowerCase(Locale.ROOT));
            if (uri.getPort() >= 0) {
                safeUrl.append(':').append(uri.getPort());
            }
            if (StringUtils.hasText(uri.getRawPath())) {
                safeUrl.append(uri.getRawPath());
            }
            String safeQuery = removeTrackingParameters(uri.getRawQuery());
            if (StringUtils.hasText(safeQuery)) {
                safeUrl.append('?').append(safeQuery);
            }
            return safeUrl.toString().replace(")", "%29");
        }
        catch (URISyntaxException exception) {
            return "";
        }
    }

    private boolean isTrustedHost(String host, Set<String> trustedDomains) {
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return trustedDomains.stream().anyMatch(domain -> normalizedHost.equals(domain)
                || normalizedHost.endsWith("." + domain));
    }

    private String removeTrackingParameters(String rawQuery) {
        if (!StringUtils.hasText(rawQuery)) {
            return "";
        }
        return Arrays.stream(rawQuery.split("&"))
                .filter(parameter -> !isTrackingParameter(parameter))
                .collect(Collectors.joining("&"));
    }

    private boolean isTrackingParameter(String parameter) {
        String name = parameter;
        int equalsIndex = parameter.indexOf('=');
        if (equalsIndex >= 0) {
            name = parameter.substring(0, equalsIndex);
        }
        try {
            name = URLDecoder.decode(name, StandardCharsets.UTF_8);
        }
        catch (IllegalArgumentException exception) {
            return true;
        }
        String normalizedName = name.toLowerCase(Locale.ROOT);
        return normalizedName.startsWith("utm_")
                || Set.of("fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid", "ref_src", "igshid")
                .contains(normalizedName);
    }

    private String citation(int index) {
        return "N" + String.format("%02d", index + 1);
    }

    private void validateMailConfiguration() {
        if (!StringUtils.hasText(config.getFrom()) || !StringUtils.hasText(config.getTo())) {
            throw new IllegalStateException("必须配置 news.mail.from 和 news.mail.to");
        }
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(Math.max(0, config.getRetryBackoffMillis()));
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("邮件重试过程被中断", exception);
        }
    }

    private record RenderedMail(String html, String plainText, EmailView view) {
    }

    public static final class EmailView {

        private final String brandName;
        private final String dateText;
        private final int estimatedReadMinutes;
        private final String overview;
        private final List<EmailSection> sections;
        private final int articleCount;

        private EmailView(String brandName, String dateText, int estimatedReadMinutes, String overview,
                List<EmailSection> sections, int articleCount) {
            this.brandName = brandName;
            this.dateText = dateText;
            this.estimatedReadMinutes = estimatedReadMinutes;
            this.overview = overview;
            this.sections = List.copyOf(sections);
            this.articleCount = articleCount;
        }

        public String getBrandName() {
            return brandName;
        }

        public String getDateText() {
            return dateText;
        }

        public int getEstimatedReadMinutes() {
            return estimatedReadMinutes;
        }

        public String getOverview() {
            return overview;
        }

        public List<EmailSection> getSections() {
            return sections;
        }

        public int articleCount() {
            return articleCount;
        }
    }

    public static final class EmailSection {

        private final String displayName;
        private final List<EmailItem> items;

        private EmailSection(String displayName, List<EmailItem> items) {
            this.displayName = displayName;
            this.items = List.copyOf(items);
        }

        public String getDisplayName() {
            return displayName;
        }

        public List<EmailItem> getItems() {
            return items;
        }
    }

    public static final class EmailItem {

        private final String title;
        private final String summary;
        private final String sourceName;
        private final String publishedAtText;
        private final String originalLink;

        private EmailItem(String title, String summary, String sourceName, String publishedAtText,
                String originalLink) {
            this.title = title;
            this.summary = summary;
            this.sourceName = sourceName;
            this.publishedAtText = publishedAtText;
            this.originalLink = originalLink;
        }

        public String getTitle() {
            return title;
        }

        public String getSummary() {
            return summary;
        }

        public String getSourceName() {
            return sourceName;
        }

        public String getPublishedAtText() {
            return publishedAtText;
        }

        public String getOriginalLink() {
            return originalLink;
        }
    }
}
