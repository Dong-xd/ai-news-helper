package com.ai.news.task;

import com.ai.news.service.NewsDigestService;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class NewsScheduleTask {
    @Autowired
    private NewsDigestService newsDigestService;

    @Scheduled(cron = "${news.schedule.cron}", zone = "${news.schedule.zone}")
    public void run() {
        log.info("开始执行定时 AI 新闻摘要任务");
        newsDigestService.executeDailyDigest();
    }
}
