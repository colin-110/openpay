package com.openpay.payment.config;

import com.openpay.payment.infrastructure.FraudScreeningClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.openpay.security.InternalHttpClients;
import org.springframework.web.client.RestClient;

@Configuration
public class FraudScreeningConfiguration {

    /**
     * Tighter timeouts than the router client, because this one is in the write path of every
     * payment rather than behind a merchant opening a panel. A gate that takes two seconds to
     * answer has already cost more than it saves.
     */
    @Bean
    @ConditionalOnMissingBean(FraudScreeningClient.class)
    public FraudScreeningClient fraudScreeningClient(
            @Value("${openpay.fraud.base-url:http://localhost:8089}") String baseUrl,
            @Value("${openpay.fraud.fail-open:true}") boolean failOpen,
            @Value("${openpay.security.internal-token:}") String internalToken) {
        // Called once per payment creation, in the merchant's request path.
        return new FraudScreeningClient(
                RestClient.builder()
                        .baseUrl(baseUrl)
                        .requestFactory(InternalHttpClients.pooled(
                                Duration.ofMillis(500), Duration.ofSeconds(1), 100))
                        .build(),
                internalToken,
                failOpen);
    }
}
