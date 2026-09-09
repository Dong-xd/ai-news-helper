package com.ai.news.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 邮件发送和邮件内容展示相关配置。
 */
@ConfigurationProperties(prefix = "news.mail")
@Data
public class MailConfig {

    /**
     * 是否启用邮件发送；关闭后仍可执行抓取和摘要流程。
     */
    private boolean enabled = true;

    /**
     * 发件人邮箱。
     */
    private String from;
    /**
     * 发件人显示名称。
     */
    private String fromName;
    /**
     * 邮件中显示的品牌名称。
     */
    private String brandName = "AI速递";
    /**
     * 收件人邮箱。
     */
    private String to;
    /**
     * 是否展示符合安全校验的原文链接。
     */
    private boolean showOriginalLinks = true;
    /**
     * 允许展示原文链接的域名，多个域名使用逗号分隔。
     */
    private String trustedLinkDomains = "";

    /**
     * 邮件主题前缀。
     */
    private String subjectPrefix = "AI 前沿速递";

    /**
     * 邮件发送失败时的最大尝试次数，实际执行至少尝试一次。
     */
    private int sendRetryAttempts = 2;

    /**
     * 邮件发送重试前等待的毫秒数。
     */
    private long retryBackoffMillis = 1000;
}
