package com.work.nonce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot 启动入口，运行后即可通过 REST 接口体验组件能力。
 */
@SpringBootApplication
@EnableScheduling
public class NonceDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(NonceDemoApplication.class, args);
    }
}

