package com.se.frms.rulecache;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class RuleCacheServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RuleCacheServiceApplication.class, args);
    }
}