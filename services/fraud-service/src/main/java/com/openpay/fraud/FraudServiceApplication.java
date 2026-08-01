package com.openpay.fraud;

import com.openpay.fraud.application.FraudProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(FraudProperties.class)
public class FraudServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudServiceApplication.class, args);
    }
}
