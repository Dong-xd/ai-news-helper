package com.ai.news.config;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RSS 新闻源抓取、过滤和并发控制配置。
 */
@ConfigurationProperties(prefix = "news.rss")
@Data
public class RssProperties {

    /**
     * 只保留最近多少小时内发布的新闻。
     */
    private int windowHours = 24;

    /**
     * 最多选择多少条新闻进入摘要流程；该值是上限而不是保证数量。
     */
    private int maxItems = 15;

    /**
     * 单条 RSS 正文允许保留的最大字符数。
     */
    private int maxContentChars = 5000;

    /**
     * 单个 RSS 来源 HTTP 请求的超时时间，单位为秒。
     */
    private int requestTimeoutSeconds = 20;

    /**
     * 同时抓取的 RSS 来源最大数量。
     */
    private int maxConcurrentSources = 4;

    /**
     * 所有 RSS 来源的整体抓取超时时间，单位为秒。
     */
    private int fetchTimeoutSeconds = 60;

    /**
     * 单个 RSS 响应允许读取的最大字节数。
     */
    private int maxFeedBytes = 1_048_576;

    /**
     * 请求 RSS 来源时使用的 User-Agent。
     */
    private String userAgent = "ai-news-helper/0.0.1";

    /**
     * 国内新闻在最终选择结果中的目标占比，实际结果会根据可用来源数量调整。
     */
    private double domesticRatio = 0.5;

    /**
     * 配置的 RSS 来源列表。
     */
    private List<Source> sources = new ArrayList<>();

    /**
     * 单个 RSS 来源的配置项。
     */
    @Data
    public static class Source {

        /**
         * 来源的稳定唯一标识。
         */
        private String id;

        /**
         * 来源展示名称。
         */
        private String name;

        /**
         * RSS 或 Atom 订阅地址。
         */
        private String url;

        /**
         * 来源区域，通常为“国内”或“国际”。
         */
        private String region = "国际";

        /**
         * 是否启用该来源。
         */
        private boolean enabled = true;

        /**
         * 订阅格式，例如 rss 或 atom，当前由订阅内容自动解析。
         */
        private String format;

        /**
         * 来源内容语言标识，例如 zh 或 en。
         */
        private String lang;

        /**
         * 来源分类，例如 news、blog 或 research。
         */
        private String category;

        /**
         * 创建空的 RSS 来源配置，供配置绑定和序列化使用。
         */
        public Source() {
        }

        /**
         * 创建一个默认归类为国际来源的 RSS 配置。
         *
         * @param id 来源唯一标识
         * @param name 来源名称
         * @param url RSS 订阅地址
         * @param enabled 是否启用
         */
        public Source(String id, String name, String url, boolean enabled) {
            this(id, name, url, enabled, "国际");
        }

        /**
         * 创建一个 RSS 来源配置。
         *
         * @param id 来源唯一标识
         * @param name 来源名称
         * @param url RSS 订阅地址
         * @param enabled 是否启用
         * @param region 来源区域
         */
        public Source(String id, String name, String url, boolean enabled, String region) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.enabled = enabled;
            this.region = region == null ? "国际" : region;
        }
    }
}
