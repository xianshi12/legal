package com.fatongai.legalassistant;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.fatongai.legalassistant.**.mapper")
@EnableScheduling
@EnableRabbit
public class LegalAssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(LegalAssistantApplication.class, args);
    }
}

