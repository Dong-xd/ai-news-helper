package com.ai.news.model.dto;

import java.util.Objects;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class NewsSummary {

    /**
     * 新闻摘要的 Markdown 内容。
     */
    private String markdown;

    /**
     * 是否使用了兜底摘要内容。
     */
    private boolean fallback;

    /**
     * 摘要生成失败的原因。
     */
    private String failureReason;

    @Builder
    public NewsSummary(String markdown, boolean fallback, String failureReason) {
        this.markdown = Objects.requireNonNull(markdown);
        this.fallback = fallback;
        this.failureReason = failureReason == null ? "" : failureReason;
    }

}
