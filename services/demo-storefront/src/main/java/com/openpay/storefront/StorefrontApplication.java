package com.openpay.storefront;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * A pretend shop that takes a real payment through the platform.
 *
 * <p>Everything else in this repository proves the gateway works to someone who already knows what
 * a payment gateway is: curl transcripts, an acceptance suite, a merchant dashboard showing rows
 * that arrived from somewhere. This exists so that the thing can be <em>watched</em> — a checkout,
 * a Pay button, and a payment visibly moving from accepted to captured without anyone touching it,
 * which is the part the architecture is actually about and the only part that has never been
 * visible.
 *
 * <p>It is deliberately not a merchant integration example. It is the shortest path from "I cloned
 * this" to "I have seen it take a payment".
 */
@SpringBootApplication
@EnableConfigurationProperties(StorefrontProperties.class)
public class StorefrontApplication {

    public static void main(String[] args) {
        SpringApplication.run(StorefrontApplication.class, args);
    }
}
