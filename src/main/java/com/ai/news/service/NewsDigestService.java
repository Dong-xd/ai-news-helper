package com.ai.news.service;

import com.ai.news.client.ai.AiSummaryClient;
import com.ai.news.client.mail.MailSendClient;
import com.ai.news.config.NewsExecutionConfig;
import com.ai.news.config.ScheduleConfig;
import com.ai.news.model.dto.NewsDigest;
import com.ai.news.model.dto.NewsItem;
import com.ai.news.model.dto.NewsSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

@Service
@Slf4j
public class NewsDigestService {

    @Autowired
    private NewsSelectionService newsSelectionService;
    @Autowired
    private AiSummaryClient aiSummaryClient;
    @Autowired
    private MailSendClient mailSendClient;
    @Autowired
    private ScheduleConfig scheduleConfig;
    @Autowired
    private NewsExecutionConfig executionConfig;
    private final ReentrantLock executionLock = new ReentrantLock();
    private final AtomicReference<LocalDate> lastSuccessfulSendDate = new AtomicReference<>();

    public void executeDailyDigest() {
        executeDailyDigest(Instant.now());
    }

    public void executeDailyDigest(Instant now) {
        if (!executionLock.tryLock()) {
            log.warn("每日新闻摘要任务正在执行，跳过本次重叠任务");
            return;
        }
        long startNanos = System.nanoTime();
        LocalDate digestDate = LocalDate.ofInstant(now, zoneId());
        try {
            if (!executionConfig.isDryRun() && digestDate.equals(lastSuccessfulSendDate.get())) {
                log.info("本进程当天已发送新闻摘要，日期={}", digestDate);
                return;
            }
            List<NewsItem> newsItems = newsSelectionService.fetchRecent(now);
            log.info("RSS 新闻抓取完成，日期={}，新闻数量={}", digestDate, newsItems.size());
            if (newsItems.isEmpty()) {
                log.info("最近没有找到 AI 新闻，不发送邮件，日期={}", digestDate);
                return;
            }
            NewsSummary summary = summarizeWithFallback(newsItems);
            NewsDigest digest = new NewsDigest(digestDate, newsItems, summary.getMarkdown(), summary.isFallback());
            if (executionConfig.isDryRun()) {
                log.info("试运行模式已启用，跳过邮件发送，日期={}，新闻数量={}，摘要内容如下：\n{}", digestDate,
                        newsItems.size(), summary.getMarkdown());
            }
            else {
                mailSendClient.send(digest);
                lastSuccessfulSendDate.set(digestDate);
            }
            log.info("每日新闻摘要任务完成，日期={}，新闻数量={}，是否降级={}，是否试运行={}，耗时={}毫秒",
                    digestDate, newsItems.size(), summary.isFallback(), executionConfig.isDryRun(),
                    elapsedMillis(startNanos));
        }
        catch (Exception exception) {
            log.error("每日新闻摘要任务失败，日期={}，耗时={}毫秒", digestDate, elapsedMillis(startNanos), exception);
        }
        finally {
            executionLock.unlock();
        }
    }

    LocalDate lastSuccessfulSendDate() {
        return lastSuccessfulSendDate.get();
    }

    private NewsSummary summarizeWithFallback(List<NewsItem> newsItems) {
        try {
            return aiSummaryClient.summarize(newsItems);
        }
        catch (Exception exception) {
            log.error("AI 摘要发生未预期异常，使用 RSS 内容降级", exception);
            return aiSummaryClient.fallback(newsItems, exception.getMessage());
        }
    }

    private ZoneId zoneId() {
        return ZoneId.of(scheduleConfig.getZone());
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
