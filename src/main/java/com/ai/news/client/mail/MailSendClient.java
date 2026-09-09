package com.ai.news.client.mail;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.ai.news.config.MailConfig;
import com.ai.news.model.dto.NewsDigest;
import com.ai.news.model.dto.NewsDigestJson;
import com.ai.news.model.dto.NewsJsonItem;
import jakarta.mail.internet.MimeMessage;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * 邮件发送客户端。
 *
 * <p>该客户端负责将结构化新闻摘要转换为 Thymeleaf 邮件视图，同时生成纯文本正文，
 * 最后通过 SMTP 发送 HTML 和纯文本双格式邮件。</p>
 */
@Component
@Slf4j
public class MailSendClient {

    /**
     * 邮件主题中的日期格式。
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日");

    /**
     * 邮件新闻条目发布时间的完整展示格式。
     */
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm");

    /**
     * 邮件头部日期和星期的展示格式。
     */
    private static final DateTimeFormatter DATE_WITH_WEEKDAY_FORMATTER = DateTimeFormatter.ofPattern(
            "yyyy年MM月dd日 EEEE", Locale.SIMPLIFIED_CHINESE);

    /**
     * 邮件展示时间使用的北京时间时区。
     */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * AI 输出的小时分钟时间格式。
     */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * 新闻板块名称到带图标邮件标题的映射。
     */
    private static final Map<String, String> BOARD_DISPLAY_NAMES = Map.of(
            "重磅发布", "🚀 重磅发布",
            "技术前沿", "🔬 技术前沿",
            "行业动态", "📊 行业动态",
            "开源工具", "🧰 开源工具");

    /**
     * Spring Boot 提供的邮件发送器。
     */
    @Autowired
    private JavaMailSender mailSender;

    /**
     * Thymeleaf 模板引擎，用于渲染 HTML 邮件正文。
     */
    @Autowired
    private TemplateEngine templateEngine;

    /**
     * 邮件地址、主题、链接和重试策略配置。
     */
    @Autowired
    private MailConfig config;

    /**
     * 发送一份新闻摘要邮件，并在发送失败时按配置重试。
     *
     * @param digest 待发送的新闻摘要
     * @throws IllegalStateException 邮件配置无效、模板无法渲染或多次发送均失败时抛出
     */
    public void send(NewsDigest digest) {
        if (!config.isEnabled()) {
            // 邮件功能关闭时直接返回，不执行配置校验、模板渲染或 SMTP 调用。
            log.info("邮件发送已禁用");
            return;
        }
        // 发送前先确认必要地址存在，再构建一次可复用的邮件内容。
        validateMailConfiguration();
        RenderedMail renderedMail = render(digest);
        // 将重试次数限制为至少一次，避免无效配置导致完全不发送。
        int attempts = Math.max(1, config.getSendRetryAttempts());
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                // 每次尝试只执行 SMTP 创建和发送，渲染结果在重试间复用。
                sendOnce(renderedMail, digest);
                log.info("AI 新闻邮件发送成功，日期={}，新闻数量={}", digest.getDate(),
                        renderedMail.view().articleCount());
                return;
            }
            catch (MailException | IllegalStateException exception) {
                // 记录当前失败；仍有剩余次数时等待后重新发送。
                lastFailure = exception;
                log.warn("邮件第 {} 次发送尝试失败，原因={}", attempt, exception.getMessage());
                if (attempt < attempts) {
                    sleepBeforeRetry();
                }
            }
        }
        // 达到最大尝试次数仍失败，将最后一次异常交给业务编排层记录。
        throw lastFailure;
    }

    /**
     * 执行一次 MIME 邮件创建和发送操作。
     *
     * @param renderedMail 已渲染的 HTML、纯文本和邮件视图
     * @param digest 新闻摘要，用于生成邮件主题
     */
    private void sendOnce(RenderedMail renderedMail, NewsDigest digest) {
        try {
            // 创建 UTF-8 MIME 邮件，并同时准备 HTML 与纯文本正文。
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(config.getFrom(), config.getFromName());
            helper.setTo(config.getTo());
            helper.setSubject(config.getSubjectPrefix() + " - " + digest.getDate().format(DATE_FORMATTER));
            helper.setText(renderedMail.plainText(), renderedMail.html());
            // 交由 Spring MailSender 执行实际 SMTP 发送。
            mailSender.send(message);
        }
        catch (Exception exception) {
            // 将底层邮件创建、地址或传输异常统一包装为业务异常。
            throw new IllegalStateException("无法创建或发送 MIME 邮件", exception);
        }
    }

    /**
     * 将新闻摘要渲染为 HTML 和纯文本两种邮件正文。
     *
     * @param digest 待渲染的新闻摘要
     * @return 已渲染的邮件内容
     */
    private RenderedMail render(NewsDigest digest) {
        // 先把结构化摘要转换为模板视图，再生成 HTML 和纯文本两种正文。
        EmailView view = buildEmailView(digest);
        // 使用简体中文区域设置渲染模板中的日期和文本。
        Context context = new Context(Locale.SIMPLIFIED_CHINESE, Map.of("mail", view));
        String html = templateEngine.process("news-email", context);
        // HTML 与纯文本共享同一视图，确保两种格式展示内容一致。
        return new RenderedMail(html, toPlainText(view), view);
    }

    /**
     * 将结构化摘要转换为 Thymeleaf 使用的邮件视图模型。
     *
     * @param digest 待转换的新闻摘要
     * @return 邮件视图模型
     */
    private EmailView buildEmailView(NewsDigest digest) {
        // 按固定顺序读取四个 JSON 板块，转换为模板需要的邮件板块列表。
        NewsDigestJson json = digest.getJson();
        List<EmailSection> sections = List.of(
                buildEmailSection("重磅发布", json.getHeavyRelease()),
                buildEmailSection("技术前沿", json.getTechFrontier()),
                buildEmailSection("行业动态", json.getIndustryNews()),
                buildEmailSection("开源工具", json.getOpenSourceTools()));
        int articleCount = sections.stream().mapToInt(section -> section.getItems().size()).sum();
        if (articleCount == 0) {
            // 没有可展示新闻时拒绝发送空邮件。
            throw new IllegalStateException("邮件没有可展示的新闻内容");
        }

        // 根据实际板块内容生成概览，并估算阅读时间供邮件头部展示。
        String overview = buildOverview(sections);
        int estimatedReadMinutes = Math.max(1, (int) Math.ceil(articleCount / 4.0));
        String dateText = digest.getDate().format(DATE_WITH_WEEKDAY_FORMATTER);
        return new EmailView(config.getBrandName(), dateText, estimatedReadMinutes, overview, sections, articleCount);
    }

    /**
     * 将一个新闻板块转换为邮件板块视图。
     *
     * @param boardName 板块中文名称
     * @param jsonItems 板块中的结构化新闻条目
     * @return 邮件板块视图
     */
    private EmailSection buildEmailSection(String boardName, List<NewsJsonItem> jsonItems) {
        List<EmailItem> items = new ArrayList<>();
        for (NewsJsonItem jsonItem : jsonItems) {
            // 清洗展示字段，并只保留经过安全链接处理后的原文地址。
            items.add(new EmailItem(
                    displayText(jsonItem.getTitle()),
                    displayText(jsonItem.getSummary()),
                    displayText(jsonItem.getSource()),
                    formatPublishedAt(jsonItem.getPublishedAt(), jsonItem.getPublishTime()),
                    jsonItem.getUrl()));
        }
        // 将内部板块名转换为带图标的邮件展示名称。
        return new EmailSection(BOARD_DISPLAY_NAMES.getOrDefault(boardName, boardName), items);
    }

    /**
     * 优先使用 AI 输出的北京时间显示时间；格式无效时回退到原始时间。
     *
     * @param publishedAt 原始发布时间
     * @param publishTime AI 输出的 HH:mm 时间
     * @return 新闻发布时间文本
     */
    private String formatPublishedAt(Instant publishedAt, String publishTime) {
        try {
            // 优先使用 AI 给出的北京时间，仅取原始日期与 AI 小时分钟组合展示。
            LocalTime time = LocalTime.parse(publishTime, TIME_FORMATTER);
            LocalDateTime dateTime = publishedAt.atZone(DISPLAY_ZONE).toLocalDate().atTime(time);
            return dateTime.format(DATE_TIME_FORMATTER);
        }
        catch (DateTimeParseException exception) {
            // AI 时间格式不合法时回退到 RSS 原始发布时间，保证邮件仍可显示时间。
            return publishedAt.atZone(DISPLAY_ZONE).format(DATE_TIME_FORMATTER);
        }
    }

    /**
     * 根据非空板块生成邮件顶部的今日速览。
     *
     * @param sections 邮件板块列表
     * @return 今日速览文本
     */
    private String buildOverview(List<EmailSection> sections) {
        // 只统计有新闻的板块，避免概览出现空板块信息。
        int articleCount = sections.stream().mapToInt(section -> section.getItems().size()).sum();
        String categoryText = sections.stream()
                .filter(section -> !section.getItems().isEmpty())
                .map(section -> section.getDisplayName() + "（" + section.getItems().size() + " 条）")
                .collect(Collectors.joining("、"));
        return "共收录 " + articleCount + " 条新闻，覆盖" + categoryText + "。本期内容已按新闻重要性和发布时间整理。";
    }

    /**
     * 根据邮件视图生成纯文本正文，供不支持 HTML 的邮件客户端使用。
     *
     * @param view 邮件视图模型
     * @return 纯文本邮件正文
     */
    private String toPlainText(EmailView view) {
        // 纯文本正文与 HTML 模板使用同一视图，兼容不支持 HTML 的邮件客户端。
        StringBuilder text = new StringBuilder()
                .append(view.getBrandName()).append(" | ").append(view.getDateText())
                .append(" · 预计阅读").append(view.getEstimatedReadMinutes()).append("分钟\n\n")
                .append("今日速览：").append(view.getOverview()).append('\n');
        for (EmailSection section : view.getSections()) {
            if (section.getItems().isEmpty()) {
                // 空板块不写入纯文本正文，保持内容紧凑。
                continue;
            }
            text.append('\n').append(section.getDisplayName()).append('\n');
            for (EmailItem item : section.getItems()) {
                text.append('\n').append(item.getTitle());
                if (StringUtils.hasText(item.getOriginalLink())) {
                    // 仅当链接通过前置安全处理后非空时才附加原文地址。
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

    /**
     * 清理展示文本中的多余空白。
     *
     * @param value 待清理文本
     * @return 适合邮件展示的文本
     */
    private String displayText(String value) {
        if (value == null) {
            // 模板字段为空时使用空字符串，避免渲染出 null 文本。
            return "";
        }
        // 把换行等连续空白压缩为单个空格，防止破坏邮件布局。
        return value.replaceAll("\\s+", " ").trim();
    }

    /**
     * 判断链接主机是否属于配置的可信域名或其子域名。
     *
     * @param host 链接主机名
     * @param trustedDomains 可信域名集合
     * @return 属于可信域名时返回 true
     */
    private boolean isTrustedHost(String host, Set<String> trustedDomains) {
        // 同时允许精确域名和其子域名，避免简单后缀匹配误信任相似域名。
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return trustedDomains.stream().anyMatch(domain -> normalizedHost.equals(domain)
                || normalizedHost.endsWith("." + domain));
    }

    /**
     * 从查询字符串中移除常见广告和跟踪参数。
     *
     * @param rawQuery 原始查询字符串
     * @return 清理后的查询字符串
     */
    private String removeTrackingParameters(String rawQuery) {
        if (!StringUtils.hasText(rawQuery)) {
            // 没有查询参数时保持空查询字符串。
            return "";
        }
        // 按参数拆分并删除常见跟踪字段，保留业务查询参数。
        return Arrays.stream(rawQuery.split("&"))
                .filter(parameter -> !isTrackingParameter(parameter))
                .collect(Collectors.joining("&"));
    }

    /**
     * 判断查询参数是否为常见跟踪参数。
     *
     * @param parameter 查询参数
     * @return 属于跟踪参数或无法安全解码时返回 true
     */
    private boolean isTrackingParameter(String parameter) {
        // 只分析参数名，参数值不会影响其是否属于跟踪字段。
        String name = parameter;
        int equalsIndex = parameter.indexOf('=');
        if (equalsIndex >= 0) {
            name = parameter.substring(0, equalsIndex);
        }
        try {
            // 先进行 URL 解码，兼容被编码的参数名称。
            name = URLDecoder.decode(name, StandardCharsets.UTF_8);
        }
        catch (IllegalArgumentException exception) {
            // 无法解码的参数无法确认安全性，按跟踪参数移除。
            return true;
        }
        String normalizedName = name.toLowerCase(Locale.ROOT);
        // 过滤 UTM 系列和常见平台点击标识，避免邮件链接携带无关追踪信息。
        return normalizedName.startsWith("utm_")
                || Set.of("fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid", "ref_src", "igshid")
                .contains(normalizedName);
    }

    /**
     * 校验发送邮件所需的发件人和收件人配置。
     */
    private void validateMailConfiguration() {
        if (!StringUtils.hasText(config.getFrom()) || !StringUtils.hasText(config.getTo())) {
            // 缺少任一地址都无法构造合法邮件，因此在 SMTP 调用前快速失败。
            throw new IllegalStateException("必须配置 news.mail.from 和 news.mail.to");
        }
    }

    /**
     * 按配置等待下一次邮件发送，并在中断时恢复线程中断标记。
     */
    private void sleepBeforeRetry() {
        try {
            // 按配置等待下一次发送，避免 SMTP 失败时立即重复请求。
            Thread.sleep(Math.max(0, config.getRetryBackoffMillis()));
        }
        catch (InterruptedException exception) {
            // 恢复中断标记，保留线程取消语义。
            Thread.currentThread().interrupt();
            throw new IllegalStateException("邮件重试过程被中断", exception);
        }
    }

    /**
     * 邮件渲染结果的内部载体。
     *
     * @param html HTML 邮件正文
     * @param plainText 纯文本邮件正文
     * @param view Thymeleaf 邮件视图模型
     */
    private record RenderedMail(String html, String plainText, EmailView view) {
    }

    /**
     * Thymeleaf 邮件模板的根视图模型。
     */
    @Data
    public static final class EmailView {

        /**
         * 邮件中展示的品牌名称。
         */
        private final String brandName;

        /**
         * 邮件头部展示的日期和星期。
         */
        private final String dateText;

        /**
         * 根据新闻数量估算的阅读分钟数。
         */
        private final int estimatedReadMinutes;

        /**
         * 邮件顶部的新闻概览文本。
         */
        private final String overview;

        /**
         * 按板块组织的邮件内容。
         */
        private final List<EmailSection> sections;

        /**
         * 所有板块中的新闻总数。
         */
        private final int articleCount;

        /**
         * 创建邮件根视图，并复制板块列表以保证视图内容稳定。
         *
         * @param brandName 品牌名称
         * @param dateText 日期文本
         * @param estimatedReadMinutes 预计阅读分钟数
         * @param overview 新闻概览
         * @param sections 邮件板块
         * @param articleCount 新闻总数
         */
        private EmailView(String brandName, String dateText, int estimatedReadMinutes, String overview,
                List<EmailSection> sections, int articleCount) {
            this.brandName = brandName;
            this.dateText = dateText;
            this.estimatedReadMinutes = estimatedReadMinutes;
            this.overview = overview;
            this.sections = List.copyOf(sections);
            this.articleCount = articleCount;
        }

        /**
         * 获取新闻总数，供发送日志读取。
         *
         * @return 新闻总数
         */
        public int articleCount() {
            return articleCount;
        }
    }

    /**
     * 邮件中的一个新闻板块。
     */
    @Data
    public static final class EmailSection {

        /**
         * 带图标的板块展示名称。
         */
        private final String displayName;

        /**
         * 当前板块的新闻条目。
         */
        private final List<EmailItem> items;

        /**
         * 创建邮件板块并复制新闻条目列表。
         *
         * @param displayName 板块展示名称
         * @param items 板块新闻条目
         */
        private EmailSection(String displayName, List<EmailItem> items) {
            this.displayName = displayName;
            this.items = List.copyOf(items);
        }
    }

    /**
     * 邮件中展示的单条新闻。
     */
    @Data
    public static final class EmailItem {

        /**
         * 新闻标题。
         */
        private final String title;

        /**
         * 新闻摘要。
         */
        private final String summary;

        /**
         * 新闻来源名称。
         */
        private final String sourceName;

        /**
         * 格式化后的新闻发布时间。
         */
        private final String publishedAtText;

        /**
         * 经过安全校验后允许跳转的原文链接，可能为空。
         */
        private final String originalLink;

        /**
         * 创建邮件新闻条目。
         *
         * @param title 新闻标题
         * @param summary 新闻摘要
         * @param sourceName 新闻来源
         * @param publishedAtText 发布时间文本
         * @param originalLink 原文链接
         */
        private EmailItem(String title, String summary, String sourceName, String publishedAtText,
                String originalLink) {
            this.title = title;
            this.summary = summary;
            this.sourceName = sourceName;
            this.publishedAtText = publishedAtText;
            this.originalLink = originalLink;
        }
    }
}
