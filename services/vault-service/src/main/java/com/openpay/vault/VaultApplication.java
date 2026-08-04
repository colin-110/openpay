package com.openpay.vault;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Turns a card number into a token, and then forgets the card number.
 *
 * <p>Its own service rather than a package inside payment-service, and that is the entire point.
 * A card number ("PAN") is the one piece of data on this platform that is worth stealing on its
 * own, and the standard answer is not to guard it better but to <em>hold it in fewer places</em>.
 * Everything else here — the payments table, the ledger, the dashboard, the merchant's own server —
 * sees a token and a last four, and none of them could leak a card number if it tried. That
 * property is only true because this boundary exists.
 *
 * <p>Three consequences worth stating, because they are what make the boundary real rather than
 * decorative:
 *
 * <ul>
 *   <li><strong>No database.</strong> Tokens live in Redis with a short TTL and expire on their own.
 *       A table would outlive its purpose, would need a reaper nobody would notice failing, and
 *       would turn a fifteen-minute secret into a permanent one.
 *   <li><strong>Single use.</strong> Redeeming a token deletes it, atomically. A token that leaks
 *       after it has been spent is worth nothing, and a replayed one is refused rather than
 *       silently accepted.
 *   <li><strong>Nothing is logged.</strong> There is no code path in this service that writes a PAN
 *       anywhere — not at debug, not in an exception message, not in a validation error. The
 *       validation failures it returns name the <em>field</em>, never the value.
 * </ul>
 *
 * <p>What this is not: PCI-DSS compliance. Compliance is an audited process about people, networks
 * and evidence, and no amount of code grants it. This is the architecture that makes the audit
 * scope small — which is the part a payment platform actually designs for.
 */
@SpringBootApplication
@EnableConfigurationProperties(VaultProperties.class)
public class VaultApplication {

    public static void main(String[] args) {
        SpringApplication.run(VaultApplication.class, args);
    }
}
