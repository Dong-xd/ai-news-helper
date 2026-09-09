package com.ai.news;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 新闻助手的 Spring Boot 启动入口。
 *
 * <p>该类负责启动 Spring 容器、启用定时任务，并扫描配置属性类；具体业务由各层组件负责。</p>
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
@Slf4j
public class AiNewsHelperApplication {

    /**
     * 启动 AI 新闻助手应用。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AiNewsHelperApplication.class, args);
    }

    /**
     * 应用启动完成后输出服务访问地址，便于确认应用已经正常运行。
     *
     * @param event Spring 应用启动完成事件
     */
    @EventListener(ApplicationReadyEvent.class)
    public void printStartupMessage(ApplicationReadyEvent event) {
        Environment environment = event.getApplicationContext().getEnvironment();
        String port = environment.getProperty("local.server.port",
                environment.getProperty("server.port", "8080"));
        String address = environment.getProperty("server.address", "localhost");
        String displayAddress = "0.0.0.0".equals(address) ? "localhost" : address;

        log.info("AI 新闻助手后台启动成功！");
        log.info("服务地址：http://{}:{}", displayAddress, port);
        log.info("服务端口：{}", port);
    }
}
