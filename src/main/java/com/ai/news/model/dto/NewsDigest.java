package com.ai.news.model.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一次每日新闻摘要任务的完整业务对象。
 */
@Data
@NoArgsConstructor
public class NewsDigest {

    /**
     * 摘要对应的日期。
     */
    private LocalDate date;

    /**
     * 当日新闻列表。
     */
    private List<NewsItem> newsItems;

    /**
     * 结构化 JSON 新闻摘要。
     */
    private NewsDigestJson json;

    /**
     * 是否使用了兜底摘要内容。
     */
    private boolean fallback;

    /**
     * 创建一份完整的新闻摘要对象，并复制新闻列表以避免外部修改内部数据。
     *
     * @param date 摘要日期
     * @param newsItems 本次参与摘要的原始新闻
     * @param json 结构化摘要内容
     * @param fallback 是否有内容使用 RSS 兜底
     */
    @Builder
    public NewsDigest(LocalDate date, List<NewsItem> newsItems, NewsDigestJson json, boolean fallback) {
        this.date = Objects.requireNonNull(date);
        this.newsItems = List.copyOf(newsItems);
        this.json = Objects.requireNonNull(json);
        this.fallback = fallback;
    }

}
