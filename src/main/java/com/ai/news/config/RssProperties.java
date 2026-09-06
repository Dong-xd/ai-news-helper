package com.ai.news.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "news.rss")
public class RssProperties {

    private int windowHours = 24;
    private int maxItems = 15;
    private int maxContentChars = 5000;
    private int requestTimeoutSeconds = 20;
    private int maxConcurrentSources = 4;
    private int fetchTimeoutSeconds = 60;
    private int maxFeedBytes = 1_048_576;
    private String userAgent = "ai-news-helper/0.0.1";
    private double domesticRatio = 0.5;
    private List<Source> sources = new ArrayList<>();

    public int getWindowHours() {
        return windowHours;
    }

    public void setWindowHours(int windowHours) {
        this.windowHours = windowHours;
    }

    public int getMaxItems() {
        return maxItems;
    }

    public void setMaxItems(int maxItems) {
        this.maxItems = maxItems;
    }

    public int getMaxContentChars() {
        return maxContentChars;
    }

    public void setMaxContentChars(int maxContentChars) {
        this.maxContentChars = maxContentChars;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public int getMaxConcurrentSources() {
        return maxConcurrentSources;
    }

    public void setMaxConcurrentSources(int maxConcurrentSources) {
        this.maxConcurrentSources = maxConcurrentSources;
    }

    public int getFetchTimeoutSeconds() {
        return fetchTimeoutSeconds;
    }

    public void setFetchTimeoutSeconds(int fetchTimeoutSeconds) {
        this.fetchTimeoutSeconds = fetchTimeoutSeconds;
    }

    public int getMaxFeedBytes() {
        return maxFeedBytes;
    }

    public void setMaxFeedBytes(int maxFeedBytes) {
        this.maxFeedBytes = maxFeedBytes;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public double getDomesticRatio() {
        return domesticRatio;
    }

    public void setDomesticRatio(double domesticRatio) {
        this.domesticRatio = domesticRatio;
    }

    public List<Source> getSources() {
        return sources;
    }

    public void setSources(List<Source> sources) {
        this.sources = sources;
    }

    public static class Source {

        private String id;
        private String name;
        private String url;
        private String region = "国际";
        private boolean enabled = true;

        public Source() {
        }

        public Source(String id, String name, String url, boolean enabled) {
            this(id, name, url, enabled, "国际");
        }

        public Source(String id, String name, String url, boolean enabled, String region) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.enabled = enabled;
            this.region = region == null ? "国际" : region;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
