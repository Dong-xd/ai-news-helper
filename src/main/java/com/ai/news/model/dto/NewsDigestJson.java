package com.ai.news.model.dto;

import java.util.List;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 按邮件板块组织的结构化新闻摘要。
 */
@Data
@NoArgsConstructor
public class NewsDigestJson {

    /**
     * 重磅发布板块新闻。
     */
    private List<NewsJsonItem> heavyRelease = List.of();

    /**
     * 技术前沿板块新闻。
     */
    private List<NewsJsonItem> techFrontier = List.of();

    /**
     * 行业动态板块新闻。
     */
    private List<NewsJsonItem> industryNews = List.of();

    /**
     * 开源工具板块新闻。
     */
    private List<NewsJsonItem> openSourceTools = List.of();

    /**
     * 创建结构化新闻摘要，并将空板块统一转换为空列表。
     *
     * @param heavyRelease 重磅发布板块
     * @param techFrontier 技术前沿板块
     * @param industryNews 行业动态板块
     * @param openSourceTools 开源工具板块
     */
    @Builder
    public NewsDigestJson(List<NewsJsonItem> heavyRelease, List<NewsJsonItem> techFrontier,
            List<NewsJsonItem> industryNews, List<NewsJsonItem> openSourceTools) {
        this.heavyRelease = copyOf(heavyRelease);
        this.techFrontier = copyOf(techFrontier);
        this.industryNews = copyOf(industryNews);
        this.openSourceTools = copyOf(openSourceTools);
    }

    /**
     * 复制板块列表，防止 DTO 内部集合被调用方修改。
     *
     * @param items 待复制的新闻列表
     * @return 不可变新闻列表
     */
    private static List<NewsJsonItem> copyOf(List<NewsJsonItem> items) {
        return items == null ? List.of() : List.copyOf(items);
    }
}
