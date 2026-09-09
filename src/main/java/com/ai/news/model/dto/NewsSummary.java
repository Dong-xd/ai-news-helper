package com.ai.news.model.dto;

import java.util.Objects;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 摘要客户端返回的结果及降级状态。
 */
@Data
@NoArgsConstructor
public class NewsSummary {

    /**
     * 结构化 JSON 新闻摘要。
     */
    private NewsDigestJson json;

    /**
     * 是否使用了兜底摘要内容。
     */
    private boolean fallback;

    /**
     * 摘要生成失败的原因。
     */
    private String failureReason;

    /**
     * 创建 AI 摘要结果。
     *
     * @param json 结构化新闻摘要
     * @param fallback 是否使用了 RSS 兜底内容
     * @param failureReason 摘要失败原因，可为空
     */
    @Builder
    public NewsSummary(NewsDigestJson json, boolean fallback, String failureReason) {
        this.json = Objects.requireNonNull(json);
        this.fallback = fallback;
        this.failureReason = failureReason == null ? "" : failureReason;
    }

}
