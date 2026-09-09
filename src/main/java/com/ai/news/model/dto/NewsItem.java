package com.ai.news.model.dto;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * RSS 来源解析出的单条原始新闻数据。
 */
@Data
@NoArgsConstructor
public class NewsItem {

    /**
     * 新闻源的唯一标识。
     */
    private String sourceId;

    /**
     * 新闻源名称。
     */
    private String sourceName;

    /**
     * 新闻标题。
     */
    private String title;

    /**
     * 新闻原文链接。
     */
    private String link;

    /**
     * 新闻来源区域，取值为“国内”或“国际”。
     */
    private String region;

    /**
     * 新闻发布时间。
     */
    private Instant publishedAt;

    /**
     * 新闻正文内容。
     */
    private String content;

    /**
     * 创建默认归类为国际新闻的原始新闻对象。
     *
     * @param sourceId 新闻源唯一标识
     * @param sourceName 新闻源名称
     * @param title 新闻标题
     * @param link 原文链接
     * @param publishedAt 发布时间
     * @param content 正文摘要
     */
    @Builder
    public NewsItem(String sourceId, String sourceName, String title, String link, Instant publishedAt, String content) {
        this(sourceId, sourceName, title, link, publishedAt, content, "国际");
    }

    /**
     * 创建一条原始新闻，并校验摘要流程必需的字段。
     *
     * @param sourceId 新闻源唯一标识
     * @param sourceName 新闻源名称
     * @param title 新闻标题
     * @param link 原文链接
     * @param publishedAt 发布时间
     * @param content 正文摘要，可为空
     * @param region 新闻来源区域
     */
    public NewsItem(String sourceId, String sourceName, String title, String link, Instant publishedAt, String content,
            String region) {
        this.sourceId = Objects.requireNonNull(sourceId);
        this.sourceName = Objects.requireNonNull(sourceName);
        this.title = Objects.requireNonNull(title);
        this.link = Objects.requireNonNull(link);
        this.publishedAt = Objects.requireNonNull(publishedAt);
        this.content = content == null ? "" : content;
        this.region = region == null ? "国际" : region;
    }

}
