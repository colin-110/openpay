package com.openpay.storefront;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

/**
 * What the checkout page talks to — for everything except the card itself.
 *
 * <p>The browser makes exactly one call that does not come here: it sends the card straight to the
 * platform's tokenisation endpoint, using the publishable key this controller hands it, and gets
 * back a token. Then it posts that token here. So this server sees an amount and a token, never a
 * card number, and could not leak one if it were compromised.
 *
 * <p>That split is the whole reason there are two credentials. The secret key lives here and takes
 * the payment; the publishable key lives in the page and can do nothing but tokenise. Sending the
 * card here first would be simpler by one HTTP call and would put every card this shop has ever
 * taken inside its own blast radius.
 */
@RestController
@RequestMapping("/api/checkout")
public class CheckoutController {

    private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);

    private final GatewayClient gateway;
    private final StorefrontProperties properties;
    private final Catalog catalog;

    public CheckoutController(GatewayClient gateway, StorefrontProperties properties, Catalog catalog) {
        this.gateway = gateway;
        this.properties = properties;
        this.catalog = catalog;
    }

    /** What the shop sells. Prices come from here and are never accepted from the page. */
    @GetMapping("/catalog")
    public List<Catalog.Product> catalog() {
        return catalog.products();
    }

    /** Tells the page whether this shop can actually take a payment, so it can say so up front. */
    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configured", properties.isConfigured());
        body.put("dashboardUrl", properties.getDashboardUrl());
        // Handed to the page so it can tokenise a card without that card ever reaching this server.
        // This is the only credential this endpoint returns, and the only one it may.
        body.put("publishableKey", properties.getPublishableKey());
        body.put("gatewayUrl", properties.getGatewayPublicUrl());
        // Only when a demo provisioner supplied them. A real deployment of this shop leaves these
        // blank and the page shows nothing, rather than an empty box captioned "sign in with".
        body.put("dashboardEmail", properties.getDashboardEmail());
        body.put("dashboardPassword", properties.getDashboardPassword());
        return body;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> pay(@Valid @RequestBody PayRequest request) {
        if (!properties.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "This shop has no credentials. They are normally minted at startup by "
                            + "demo-provisioner; otherwise set STOREFRONT_API_KEY and "
                            + "STOREFRONT_PUBLISHABLE_KEY."));
        }

        // Generated per attempt rather than per basket: the point of the demo is that a payment
        // happens, and a fresh key each time is what a real checkout does for a new order. The
        // client can send its own to demonstrate a retry returning the original payment.
        String idempotencyKey = request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                ? "storefront-" + UUID.randomUUID()
                : request.idempotencyKey();

        // Priced here, from the shop's own list, whenever there is a cart. The browser sends what
        // was bought; it does not get a say in what that costs.
        long amount = request.items() == null || request.items().isEmpty()
                ? request.amount()
                : catalog.total(request.items().stream()
                        .map(line -> new Catalog.CartItem(line.productId(), line.quantity()))
                        .toList());

        if (amount < 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "There is nothing in the basket."));
        }

        try {
            // The token, and nothing describing the card. The page tokenised it against the
            // platform directly, so this server has never seen a card number and could not
            // describe one if it wanted to — which is the property the whole arrangement is for.
            Map<String, Object> payment = gateway.createPayment(
                    amount, request.currency().toUpperCase(), idempotencyKey, request.token());
            log.info("Took payment {} for {} {}", payment.get("id"), amount, request.currency());
            return ResponseEntity.ok(Map.of("payment", payment, "idempotencyKey", idempotencyKey));
        } catch (RestClientResponseException refused) {
            // The interesting refusals are the platform's own: 422 when a risk rule blocks the
            // payment, 429 when the rate limiter does. Passing the status and body straight through
            // means the page can say what actually happened instead of "something went wrong".
            log.info("Gateway refused the payment: {} {}", refused.getStatusCode(),
                    refused.getResponseBodyAsString());
            return ResponseEntity.status(refused.getStatusCode())
                    .body(Map.of("error", refused.getResponseBodyAsString()));
        }
    }

    /**
     * A cart naming something this shop does not sell.
     *
     * <p>400 rather than a quiet skip: a basket the server cannot price in full is one it must not
     * charge for at all, because the alternative is billing the customer for the half it recognised.
     */
    @ExceptionHandler(Catalog.UnknownProductException.class)
    public ResponseEntity<Map<String, Object>> handleUnknownProduct(Catalog.UnknownProductException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    @GetMapping("/{paymentId}")
    // Named explicitly, as everywhere else in this repository: the build imports the Spring BOM
    // rather than inheriting spring-boot-starter-parent, so `-parameters` is not on and the name
    // cannot be recovered by reflection.
    public ResponseEntity<Map<String, Object>> status(@PathVariable("paymentId") UUID paymentId) {
        Map<String, Object> payment = gateway.getPayment(paymentId);
        return payment == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(payment);
    }

    public record PayRequest(
            /**
             * What is in the basket. When present, the amount is computed from the shop's own price
             * list and {@link #amount} is ignored entirely.
             *
             * <p>Ignored rather than cross-checked, deliberately. Comparing the two and refusing a
             * mismatch would look more careful and would be worse: it treats the client's total as
             * information, and the moment anyone relaxes that check to be helpful — a rounding
             * difference, a discount the page knows about — the price becomes the customer's to
             * choose. The server either owns the price or it does not.
             */
            @Valid @Size(max = 20) List<CartLine> items,

            // Minor units, like everywhere else in the platform — paise, not rupees. Capped well
            // under the seeded risk thresholds so the ordinary demo path is the ordinary path;
            // going over them deliberately is a different and also interesting demo.
            //
            // Only used when there is no cart, which is how scripts/demo-payment.sh and the
            // acceptance suite drive this shop — they care that a payment happens, not what was
            // bought.
            @Min(0) @Max(4_000_000) long amount,

            @NotBlank String currency,
            String idempotencyKey,
            // Minted by the browser against the platform. Optional, so a caller driving this shop
            // from a script — scripts/demo-payment.sh, the acceptance suite — can still take a
            // payment without a card, exactly as it could before.
            @Size(max = 255) String token) {
    }

    public record CartLine(
            @NotBlank @Size(max = 64) String productId,
            // Bounded so that a quantity cannot be used to drive the total past the risk rules by
            // accident, and so Catalog.total cannot overflow at any accepted input.
            @Min(1) @Max(99) int quantity) {
    }
}
