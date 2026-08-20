package com.se.frms.fraudengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FraudEngineServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FraudEngineServiceApplication.class, args);
    }
}
