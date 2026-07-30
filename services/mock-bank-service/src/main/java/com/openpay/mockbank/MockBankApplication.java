package com.openpay.mockbank;

import com.openpay.mockbank.domain.BankProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * A simulated acquiring bank.
 *
 * <p>One codebase, deployed twice as "mock-bank-a" and "mock-bank-b" with different ports and
 * behaviour. Two near-identical modules would have doubled the code to demonstrate the same thing;
 * what matters for routing and failover is two independently configurable endpoints.
 */
@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties(BankProperties.class)
public class MockBankApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockBankApplication.class, args);
    }
}
