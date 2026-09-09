package com.ai.news.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 新闻定时任务的调度配置。
 */
@ConfigurationProperties(prefix = "news.schedule")
@Data
public class ScheduleConfig {

    /**
     * 定时任务 Cron 表达式，默认每天北京时间 08:00 执行。
     */
    private String cron = "0 0 8 * * *";

    /**
     * Cron 表达式使用的时区。
     */
    private String zone = "Asia/Shanghai";
}
