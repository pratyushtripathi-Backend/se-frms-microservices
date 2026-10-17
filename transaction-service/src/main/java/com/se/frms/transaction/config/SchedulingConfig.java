package com.se.frms.transaction.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables StuckTransactionRecoveryJob (the only @Scheduled method in this service). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
