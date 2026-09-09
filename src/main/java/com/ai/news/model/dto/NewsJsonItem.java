package com.ai.news.model.dto;

import java.time.Instant;
import java.util.Objects;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 大模型整理后的单条结构化新闻摘要。
 */
@Data
@NoArgsConstructor
public class NewsJsonItem {

    /**
     * 新闻标题。
     */
    private String title;

    /**
     * 新闻核心摘要。
     */
    private String summary;

    /**
     * 新闻来源名称。
     */
    private String source;

    /**
     * 统一为北京时间 HH:mm 格式的发布时间。
     */
    private String publishTime;

    /**
     * 新闻发布时间。(2026-09-08T10:10:00Z)
     */
    private Instant publishedAt;

    /**
     * 原文链接或对应 RSS 新闻的 Nxx 引用标记。
     */
    private String url;

    /**
     * 新闻所属板块键，只允许使用重磅发布、技术前沿、行业动态或开源工具对应的键值。
     */
    private String type;

    /**
     * 创建一条结构化新闻摘要，并校验邮件渲染所需字段不为空。
     *
     * @param title 中文新闻标题
     * @param summary 新闻核心摘要
     * @param source 新闻来源名称
     * @param publishTime 北京时间的 HH:mm 展示时间
     * @param url 原文链接或 RSS 引用标记
     * @param type 新闻板块类型
     * @param publishedAt 新闻原始发布时间
     */
    @Builder
    public NewsJsonItem(String title, String summary, String source, String publishTime, String url, String type,
                        Instant publishedAt) {
        this.title = Objects.requireNonNull(title);
        this.summary = Objects.requireNonNull(summary);
        this.source = Objects.requireNonNull(source);
        this.publishTime = Objects.requireNonNull(publishTime);
        this.url = Objects.requireNonNull(url);
        this.type = Objects.requireNonNull(type);
        this.publishedAt = Objects.requireNonNull(publishedAt);
    }
}
