package com.openpay.router;

import com.openpay.router.application.RouterProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RouterProperties.class)
public class ProviderRouterApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProviderRouterApplication.class, args);
    }
}
