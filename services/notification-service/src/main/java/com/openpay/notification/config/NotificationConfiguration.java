package com.openpay.notification.config;

import com.openpay.notification.application.NotificationProperties;
import com.openpay.notification.infrastructure.MerchantConfigClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotificationConfiguration {

    @Bean
    public MerchantConfigClient merchantConfigClient(NotificationProperties properties) {
        return new MerchantConfigClient(properties);
    }
}
