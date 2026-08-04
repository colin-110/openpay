package com.openpay.vault;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openpay.vault")
public class VaultProperties {

    /**
     * How long a token is worth anything.
     *
     * <p>Long enough that a customer can tokenise, read the order summary and then press Pay without
     * being told their card expired; short enough that a token lifted from a browser's network tab
     * is worthless by the time anyone gets to it. Fifteen minutes is the same order as every hosted
     * checkout in the industry, and it is bounded by something real — Redis expiry — rather than by
     * a sweeper that has to keep running.
     */
    private Duration tokenTtl = Duration.ofMinutes(15);

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }
}
