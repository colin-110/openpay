package com.openpay.vault.application;

import com.openpay.vault.api.TokenResponse;
import com.openpay.vault.api.TokenizeRequest;
import com.openpay.vault.domain.CardNetwork;
import com.openpay.vault.domain.Luhn;
import com.openpay.vault.domain.StoredInstrument;
import com.openpay.vault.VaultProperties;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Validates an instrument and exchanges it for a token.
 *
 * <p>Every rejection below names the field and never the value. That is not fussiness: validation
 * messages are the most likely place for a card number to escape into a log, an error tracker or a
 * screenshot in a bug report, because they are written to be helpful and are rarely reviewed with
 * this in mind.
 */
@Service
public class TokenizationService {

    private static final Logger log = LoggerFactory.getLogger(TokenizationService.class);
    private static final Pattern SEPARATORS = Pattern.compile("[\\s-]");
    private static final Pattern DIGITS_ONLY = Pattern.compile("^[0-9]+$");
    /** Matches PaymentMethodRequest's own pattern, so a token can never mint a payment it refuses. */
    private static final Pattern VPA = Pattern.compile("^[\\w.\\-]{2,64}@[a-zA-Z]{2,64}$");
    /** A card is accepted this many years ahead; beyond that the expiry is a typo, not a card. */
    private static final int MAX_YEARS_AHEAD = 20;

    private final TokenStore tokenStore;
    private final VaultProperties properties;

    public TokenizationService(TokenStore tokenStore, VaultProperties properties) {
        this.tokenStore = tokenStore;
        this.properties = properties;
    }

    public TokenResponse tokenize(TokenizeRequest request, UUID merchantId) {
        StoredInstrument instrument = "upi".equals(request.type())
                ? upi(request)
                : card(request);

        String token = tokenStore.mint(instrument);
        // The type and network, never the instrument. This line exists so an operator can see that
        // tokenisation is happening at all; it must never be the reason a card number is on disk.
        log.info("Minted a {} token for merchant {}", instrument.type(), merchantId);

        return new TokenResponse(
                token,
                instrument.type(),
                instrument.network(),
                instrument.last4(),
                instrument.vpa(),
                OffsetDateTime.now().plus(properties.getTokenTtl()));
    }

    private StoredInstrument card(TokenizeRequest request) {
        if (request.number() == null || request.number().isBlank()) {
            throw new InvalidInstrumentException("number", "A card number is required");
        }
        String digits = SEPARATORS.matcher(request.number()).replaceAll("");
        if (!DIGITS_ONLY.matcher(digits).matches()) {
            throw new InvalidInstrumentException("number", "A card number may only contain digits");
        }

        // Network first: it decides both the acceptable length and the security code length, so
        // checking it before Luhn means an unsupported card is refused as unsupported rather than
        // as a bad checksum, which would be a confusing thing to tell a customer.
        CardNetwork network = CardNetwork.detect(digits);
        if (network == CardNetwork.UNKNOWN) {
            throw new InvalidInstrumentException(
                    "number", "That card network is not supported, or the number is the wrong length");
        }
        if (!Luhn.isValid(digits)) {
            throw new InvalidInstrumentException("number", "That card number is not valid");
        }
        requireExpiryInTheFuture(request);
        requireSecurityCode(request, network);

        return new StoredInstrument(
                "card",
                network.wireName(),
                digits.substring(digits.length() - 4),
                null,
                null,
                null);
    }

    private StoredInstrument upi(TokenizeRequest request) {
        if (request.vpa() == null || !VPA.matcher(request.vpa().trim()).matches()) {
            throw new InvalidInstrumentException("vpa", "That is not a valid UPI address");
        }
        String vpa = request.vpa().trim();
        // The handle names the bank and is not personal, so it survives whole. The local part is
        // masked by PaymentMethod on the way into the payments table; this keeps the same shape.
        String handle = vpa.substring(vpa.indexOf('@') + 1);
        return new StoredInstrument("upi", null, null, vpa, handle, null);
    }

    private void requireExpiryInTheFuture(TokenizeRequest request) {
        if (request.expMonth() == null || request.expMonth() < 1 || request.expMonth() > 12) {
            throw new InvalidInstrumentException("expMonth", "Expiry month must be between 1 and 12");
        }
        if (request.expYear() == null) {
            throw new InvalidInstrumentException("expYear", "An expiry year is required");
        }
        // Two-digit years are what people type on a card, and rejecting them would be pedantry.
        int year = request.expYear() < 100 ? 2000 + request.expYear() : request.expYear();
        YearMonth expiry;
        try {
            expiry = YearMonth.of(year, request.expMonth());
        } catch (RuntimeException outOfRange) {
            throw new InvalidInstrumentException("expYear", "That expiry date is not a real date");
        }
        YearMonth now = YearMonth.now();
        // A card is good through the last day of its expiry month, so equality is still valid.
        if (expiry.isBefore(now)) {
            throw new InvalidInstrumentException("expYear", "That card has expired");
        }
        if (expiry.isAfter(now.plusYears(MAX_YEARS_AHEAD))) {
            throw new InvalidInstrumentException("expYear", "That expiry date is too far in the future");
        }
    }

    private void requireSecurityCode(TokenizeRequest request, CardNetwork network) {
        String code = request.securityCode();
        if (code == null || !DIGITS_ONLY.matcher(code).matches()
                || code.length() != network.securityCodeLength()) {
            throw new InvalidInstrumentException(
                    "securityCode",
                    "A " + network.securityCodeLength() + "-digit security code is required for this card");
        }
    }
}
