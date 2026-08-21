package com.example.receipt.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class TimeConfiguration {
    @Bean
    Clock applicationClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
