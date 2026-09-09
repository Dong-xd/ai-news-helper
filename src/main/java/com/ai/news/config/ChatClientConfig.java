package com.ai.news.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 客户端配置。
 *
 * <p>应用通过限定名称选择 DashScope 聊天模型，业务层只依赖统一的 {@link ChatClient}。</p>
 */
@Configuration(proxyBeanMethods = false)
public class ChatClientConfig {

    /**
     * 创建新闻摘要专用的聊天客户端。
     *
     * @param chatModel 已配置的 DashScope 聊天模型
     * @return 用于调用大模型的聊天客户端
     */
    @Bean
    public ChatClient newsChatClient(@Qualifier("dashscopeChatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
