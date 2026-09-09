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

/**
 * 每日新闻摘要业务编排服务。
 *
 * <p>该服务串联新闻抓取、AI 摘要、降级处理和邮件发送，并在单进程内通过锁和日期标记避免重复执行。</p>
 */
@Service
@Slf4j
public class NewsDigestService {

    /**
     * 新闻选择服务，负责获取时间窗口内的 RSS 新闻。
     */
    @Autowired
    private NewsSelectionService newsSelectionService;

    /**
     * AI 摘要客户端，负责生成结构化摘要或 RSS 兜底内容。
     */
    @Autowired
    private AiSummaryClient aiSummaryClient;

    /**
     * 邮件发送客户端，负责渲染并发送摘要邮件。
     */
    @Autowired
    private MailSendClient mailSendClient;

    /**
     * 定时任务时区配置，用于计算摘要日期。
     */
    @Autowired
    private ScheduleConfig scheduleConfig;

    /**
     * 任务试运行配置，用于控制是否跳过邮件发送。
     */
    @Autowired
    private NewsExecutionConfig executionConfig;

    /**
     * 单进程任务执行锁，用于跳过重叠执行。
     */
    private final ReentrantLock executionLock = new ReentrantLock();

    /**
     * 单进程内最近一次成功发送邮件的摘要日期，用于当天幂等保护。
     */
    private final AtomicReference<LocalDate> lastSuccessfulSendDate = new AtomicReference<>();

    /**
     * 使用当前时间执行每日新闻摘要任务。
     */
    public void executeDailyDigest() {
        // 生产入口使用当前时刻，具体流程由带时间参数的方法统一处理。
        executeDailyDigest(Instant.now());
    }

    /**
     * 按指定时间执行每日新闻摘要完整流程。
     *
     * <p>流程包括新闻抓取、AI 摘要、失败降级和邮件发送；任务异常会记录日志而不会向定时线程继续抛出。</p>
     *
     * @param now 当前时间，用于计算摘要日期和新闻时间窗口
     */
    public void executeDailyDigest(Instant now) {
        // 使用非阻塞锁跳过重叠触发，避免同一进程并行发送重复邮件。
        if (!executionLock.tryLock()) {
            log.warn("每日新闻摘要任务正在执行，跳过本次重叠任务");
            return;
        }
        long startNanos = System.nanoTime();
        // 按配置时区确定本期摘要日期，避免服务器默认时区造成日期偏移。
        LocalDate digestDate = LocalDate.ofInstant(now, zoneId());
        try {
            // 非试运行模式下，同一摘要日期成功发送后直接跳过后续重复执行。
            if (!executionConfig.isDryRun() && digestDate.equals(lastSuccessfulSendDate.get())) {
                log.info("本进程当天已发送新闻摘要，日期={}", digestDate);
                return;
            }
            // 先抓取时间窗口内的新闻，再进入摘要和邮件发送阶段。
            List<NewsItem> newsItems = newsSelectionService.fetchRecent(now);
            log.info("RSS 新闻抓取完成，日期={}，新闻数量={}", digestDate, newsItems.size());
            if (newsItems.isEmpty()) {
                log.info("最近没有找到 AI 新闻，不发送邮件，日期={}", digestDate);
                return;
            }
            // AI 失败时由该方法统一转换为 RSS 兜底摘要，保证流程继续完成。
            NewsSummary summary = summarizeWithFallback(newsItems);
            // 将摘要日期、原始新闻和结构化结果组合成邮件发送对象。
            NewsDigest digest = new NewsDigest(digestDate, newsItems, summary.getJson(), summary.isFallback());
            if (executionConfig.isDryRun()) {
                // 试运行只生成并记录摘要，不产生外部 SMTP 副作用。
                log.info("试运行模式已启用，跳过邮件发送，日期={}，新闻数量={}，JSON 摘要已生成", digestDate,
                        newsItems.size());
            }
            else {
                // 只有邮件实际发送成功后才更新日期标记，确保失败执行仍可重试。
                mailSendClient.send(digest);
                lastSuccessfulSendDate.set(digestDate);
            }
            log.info("每日新闻摘要任务完成，日期={}，新闻数量={}，是否降级={}，是否试运行={}，耗时={}毫秒",
                    digestDate, newsItems.size(), summary.isFallback(), executionConfig.isDryRun(),
                    elapsedMillis(startNanos));
        }
        catch (Exception exception) {
            // 捕获完整任务异常，避免影响定时线程，并保留堆栈供排查。
            log.error("每日新闻摘要任务失败，日期={}，耗时={}毫秒", digestDate, elapsedMillis(startNanos), exception);
        }
        finally {
            // 无论任务成功、跳过还是失败，都释放执行锁。
            executionLock.unlock();
        }
    }

    /**
     * 获取当前进程最近一次成功发送的摘要日期，供测试和运行状态检查使用。
     *
     * @return 最近成功发送日期，没有成功发送记录时返回 null
     */
    LocalDate lastSuccessfulSendDate() {
        return lastSuccessfulSendDate.get();
    }

    /**
     * 调用 AI 摘要服务；发生未预期异常时将整批新闻转换为 RSS 兜底内容。
     *
     * @param newsItems 待摘要的新闻列表
     * @return AI 摘要或兜底摘要
     */
    private NewsSummary summarizeWithFallback(List<NewsItem> newsItems) {
        try {
            // 优先执行逐条 AI 摘要，单条失败由客户端内部处理为局部降级。
            return aiSummaryClient.summarize(newsItems);
        }
        catch (Exception exception) {
            // 处理客户端未预期的整批异常，确保仍能生成 RSS 结构化内容。
            log.error("AI 摘要发生未预期异常，使用 RSS 内容降级", exception);
            return aiSummaryClient.fallback(newsItems, exception.getMessage());
        }
    }

    /**
     * 将配置的时区字符串转换为 Java 时区对象。
     *
     * @return 任务使用的时区
     */
    private ZoneId zoneId() {
        // 每次按配置解析时区，使摘要日期与定时任务配置保持一致。
        return ZoneId.of(scheduleConfig.getZone());
    }

    /**
     * 根据单调时钟计算任务耗时。
     *
     * @param startNanos 任务开始时的纳秒时间
     * @return 已耗时的毫秒数
     */
    private long elapsedMillis(long startNanos) {
        // 使用单调时钟计算耗时，避免系统时间调整影响统计结果。
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
