package com.ai.news.task;

import com.ai.news.service.NewsDigestService;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AI 新闻摘要定时任务入口。
 *
 * <p>该类只负责响应 Spring 调度事件并调用业务服务，不承载新闻处理逻辑。</p>
 */
@Component
@Slf4j
public class NewsScheduleTask {

    /**
     * 每日新闻摘要业务服务。
     */
    @Autowired
    private NewsDigestService newsDigestService;

    /**
     * 触发一次每日新闻摘要业务流程。
     *
     * <p>当前 Cron 表达式用于开发阶段的周期触发，实际运行频率可根据部署配置调整。</p>
     */
    @Scheduled(cron = "${news.schedule.cron}", zone = "${news.schedule.zone}")
    public void run() {
        log.info("开始执行定时 AI 新闻摘要任务");
        newsDigestService.executeDailyDigest();
    }
}
