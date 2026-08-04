package com.openpay.vault.api;

import com.openpay.security.ApiKeyAuthenticationFilter;
import com.openpay.security.ApiKeyPrincipal;
import com.openpay.vault.application.TokenStore;
import com.openpay.vault.application.TokenizationService;
import com.openpay.vault.domain.StoredInstrument;
import jakarta.validation.Valid;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two halves of a token's life: minting one from a browser, and spending one from a service.
 *
 * <p>They are deliberately on different security tiers. Minting is reachable from the public
 * internet with a key anyone can read out of a page; redeeming is internal-only and requires the
 * service token. That asymmetry is the design: turning a card into a token is a thing strangers may
 * do, and turning a token back into anything is not.
 */
@RestController
public class TokenController {

    private final TokenizationService tokenizationService;
    private final TokenStore tokenStore;

    public TokenController(TokenizationService tokenizationService, TokenStore tokenStore) {
        this.tokenizationService = tokenizationService;
        this.tokenStore = tokenStore;
    }

    /**
     * Called by the customer's browser, directly, with the shop's publishable key.
     *
     * <p>Directly is the whole point. The card number goes from the browser to this service without
     * passing through the merchant's server, which is why the merchant's server can never leak one.
     * A shop that posted the card to its own backend first would be a shop whose backend is in scope
     * for every card it has ever taken.
     */
    @PostMapping("/api/v1/tokens")
    public ResponseEntity<TokenResponse> tokenize(
            @RequestAttribute(ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE) ApiKeyPrincipal principal,
            @Valid @RequestBody TokenizeRequest request) {

        principal.requireTokenize("tokenise an instrument");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(tokenizationService.tokenize(request, principal.merchantId()));
    }

    /**
     * Called by payment-service when it creates a payment. Spends the token and returns what the
     * instrument safely was.
     *
     * <p>This is what stops a merchant describing the payment method however it likes: the network
     * and last four on a payment come from whatever was actually tokenised, not from fields the
     * caller filled in. It is also where single-use is enforced — a second redemption of the same
     * token finds nothing.
     */
    @PostMapping("/internal/vault/redeem")
    public ResponseEntity<StoredInstrument> redeem(@Valid @RequestBody RedeemRequest request) {
        Optional<StoredInstrument> instrument = tokenStore.redeem(request.token());
        // 404 for expired, unknown and already-spent alike. A caller that could tell them apart
        // could ask this endpoint which tokens have ever existed.
        return instrument.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record RedeemRequest(String token) {
    }
}
