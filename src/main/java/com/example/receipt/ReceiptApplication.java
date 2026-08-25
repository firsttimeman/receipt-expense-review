package com.example.receipt;

import com.example.receipt.config.ReceiptProperties;
import com.example.receipt.config.ReceiptWorkerProperties;
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
