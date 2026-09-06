package com.ai.news.model.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
     * 新闻摘要的 Markdown 内容。
     */
    private String markdown;

    /**
     * 是否使用了兜底摘要内容。
     */
    private boolean fallback;

    @Builder
    public NewsDigest(LocalDate date, List<NewsItem> newsItems, String markdown, boolean fallback) {
        this.date = Objects.requireNonNull(date);
        this.newsItems = List.copyOf(newsItems);
        this.markdown = Objects.requireNonNull(markdown);
        this.fallback = fallback;
    }

}
