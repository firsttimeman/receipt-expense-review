package com.example.receipt;

import com.example.receipt.domain.extraction.config.ReceiptWorkerProperties;
import com.example.receipt.global.config.ReceiptProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        ReceiptProperties.class,
        ReceiptWorkerProperties.class
})
public class ReceiptApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReceiptApplication.class, args);
    }
}
