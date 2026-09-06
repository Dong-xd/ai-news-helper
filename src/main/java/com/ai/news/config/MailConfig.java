package com.ai.news.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "news.mail")
@Data
public class MailConfig {

    private boolean enabled = true;
    /**
     * 发件人邮箱
     */
    private String from;
    /**
     * 发件人显示名称
     */
    private String fromName;
    /**
     * 邮件中显示的品牌名称
     */
    private String brandName = "AI速递";
    /**
     * 收件人邮箱
     */
    private String to;
    /**
     * 是否展示符合安全校验的国内原文链接
     */
    private boolean showOriginalLinks = true;
    /**
     * 允许展示原文链接的域名，多个域名使用逗号分隔
     */
    private String trustedLinkDomains = "";
    private String subjectPrefix = "AI 前沿速递";
    private int sendRetryAttempts = 2;
    private long retryBackoffMillis = 1000;
}
