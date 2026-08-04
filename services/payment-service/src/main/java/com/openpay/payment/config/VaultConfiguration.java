package com.openpay.payment.config;

import com.openpay.payment.infrastructure.VaultClient;
import com.openpay.security.InternalHttpClients;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class VaultConfiguration {

    /**
     * Same tight budget as the fraud gate, and for the same reason: this call sits inside the
     * merchant's request. It differs in one important way — it does <em>not</em> fail open. A
     * screening service that cannot be reached leaves a payment unscreened, which is a bounded and
     * insurable cost; a vault that cannot be reached leaves a payment whose instrument is unknown,
     * and accepting that would mean trusting the caller's own description of the card, which is
     * precisely what redeeming the token exists to stop.
     */
    @Bean
    @ConditionalOnMissingBean(VaultClient.class)
    public VaultClient vaultClient(
            @Value("${openpay.vault.base-url:http://localhost:8091}") String baseUrl,
            @Value("${openpay.security.internal-token:}") String internalToken) {
        return new VaultClient(
                RestClient.builder()
                        .baseUrl(baseUrl)
                        .requestFactory(InternalHttpClients.pooled(
                                Duration.ofMillis(500), Duration.ofSeconds(1), 100))
                        .build(),
                internalToken);
    }
}
