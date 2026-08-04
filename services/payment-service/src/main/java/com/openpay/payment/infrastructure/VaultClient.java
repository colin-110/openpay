package com.openpay.payment.infrastructure;

import com.openpay.security.AdminTokenFilter;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Spends a token at vault-service and gets back what the customer actually paid with.
 *
 * <p>This is what makes the payment method on a payment <em>true</em> rather than merely claimed.
 * Without it, the network and last four are fields the merchant filled in, and a shop could label a
 * payment "visa 4242" regardless of what was tokenised — which would make every one of those fields
 * decoration, and the dashboard that displays them a nicely formatted guess.
 *
 * <p>It is also where single use is enforced. Redemption deletes the token, so a replayed token
 * finds nothing and the payment is refused.
 */
public class VaultClient {

    private static final Logger log = LoggerFactory.getLogger(VaultClient.class);

    private final RestClient restClient;
    private final String internalToken;

    public VaultClient(RestClient restClient, String internalToken) {
        this.restClient = restClient;
        this.internalToken = internalToken == null ? "" : internalToken;
    }

    /**
     * @return the instrument behind the token
     * @throws TokenNotRedeemableException if the token is unknown, expired, already spent, or was
     *     minted by a different merchant
     */
    public RedeemedInstrument redeem(String token, UUID merchantId) {
        RedeemedInstrument instrument;
        try {
            instrument = restClient
                    .post()
                    .uri("/internal/vault/redeem")
                    .header(AdminTokenFilter.INTERNAL_TOKEN_HEADER, internalToken)
                    .body(new RedeemRequest(token))
                    .retrieve()
                    .body(RedeemedInstrument.class);
        } catch (RestClientResponseException notFound) {
            if (notFound.getStatusCode().value() == 404) {
                throw new TokenNotRedeemableException("That payment token is invalid, expired, or already used");
            }
            throw notFound;
        }
        if (instrument == null) {
            throw new TokenNotRedeemableException("That payment token is invalid, expired, or already used");
        }

        // Fails closed, and deliberately does not say which merchant it belonged to. A token is a
        // bearer reference: without this check any merchant could spend another's, which would let
        // one shop attribute a payment to an instrument tokenised on a different shop's checkout.
        if (instrument.merchantId() != null && !instrument.merchantId().equals(merchantId)) {
            log.warn("Merchant {} tried to redeem a token minted for another merchant", merchantId);
            throw new TokenNotRedeemableException("That payment token is invalid, expired, or already used");
        }
        return instrument;
    }

    public record RedeemRequest(String token) {
    }

    /** Exactly the safe fields. There is no card number in this record because there is none to get. */
    public record RedeemedInstrument(
            String type, String network, String last4, String vpa, String bank, UUID merchantId) {
    }
}
