package com.ai.news.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 摘要生成相关配置。
 *
 * <p>提示词从配置文件读取，重试参数用于控制单条新闻的失败重试次数和退避时间。</p>
 */
@ConfigurationProperties(prefix = "news.ai")
@Data
public class AiConfig {

    /**
     * 单条新闻调用 AI 的最大尝试次数，实际执行至少尝试一次。
     */
    private int maxAttempts = 2;

    /**
     * AI 调用失败后再次尝试前等待的毫秒数。
     */
    private long retryBackoffMillis = 500;

    /**
     * 发给 AI 的系统提示词，用于约束角色、输出语言和事实边界。
     */
    private String systemPrompt = "你是一个严谨的 AI 新闻编辑，只根据提供的 RSS 资料生成符合配置契约的中文 JSON 摘要，不要胡编乱造。";

    /**
     * 发给 AI 的用户提示词模板，必须包含新闻标题、新闻类型和新闻正文占位符。
     */
    private String userPromptTemplate;
}
