package com.se.frms.decision;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DecisionServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DecisionServiceApplication.class, args);
    }
}
