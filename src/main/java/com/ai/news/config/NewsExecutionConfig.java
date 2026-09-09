package com.ai.news.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 新闻任务执行模式配置。
 */
@ConfigurationProperties(prefix = "news")
public class NewsExecutionConfig {

    /**
     * 是否启用试运行模式；启用后生成摘要但跳过邮件发送和发送日期标记。
     */
    private boolean dryRun;

    /**
     * 判断当前是否为试运行模式。
     *
     * @return true 表示跳过邮件发送，false 表示正常发送
     */
    public boolean isDryRun() {
        return dryRun;
    }

    /**
     * 设置是否启用试运行模式。
     *
     * @param dryRun true 表示跳过邮件发送
     */
    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }
}
