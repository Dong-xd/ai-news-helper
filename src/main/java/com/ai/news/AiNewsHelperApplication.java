package com.ai.news;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
@Slf4j
public class AiNewsHelperApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiNewsHelperApplication.class, args);
    }

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
