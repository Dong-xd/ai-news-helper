package com.ai.news.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "news.ai")
public class AiConfig {

    private int maxInputChars = 32000;
    private int maxOutputChars = 30000;
    private int minimumSummaryChars = 80;
    private int maximumSummaryChars = 150;
    private int maxAttempts = 2;
    private long retryBackoffMillis = 500;
    private String systemPrompt = "你是一个严谨的 AI 新闻编辑，只根据提供的 RSS 资料生成中文 Markdown 摘要，不要胡编乱造。";
    private String userPromptTemplate = "请整理以下新闻资料：\n{news-items}";

    public int getMaxInputChars() {
        return maxInputChars;
    }

    public void setMaxInputChars(int maxInputChars) {
        this.maxInputChars = maxInputChars;
    }

    public int getMaxOutputChars() {
        return maxOutputChars;
    }

    public void setMaxOutputChars(int maxOutputChars) {
        this.maxOutputChars = maxOutputChars;
    }

    public int getMinimumSummaryChars() {
        return minimumSummaryChars;
    }

    public void setMinimumSummaryChars(int minimumSummaryChars) {
        this.minimumSummaryChars = minimumSummaryChars;
    }

    public int getMaximumSummaryChars() {
        return maximumSummaryChars;
    }

    public void setMaximumSummaryChars(int maximumSummaryChars) {
        this.maximumSummaryChars = maximumSummaryChars;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getRetryBackoffMillis() {
        return retryBackoffMillis;
    }

    public void setRetryBackoffMillis(long retryBackoffMillis) {
        this.retryBackoffMillis = retryBackoffMillis;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getUserPromptTemplate() {
        return userPromptTemplate;
    }

    public void setUserPromptTemplate(String userPromptTemplate) {
        this.userPromptTemplate = userPromptTemplate;
    }
}
