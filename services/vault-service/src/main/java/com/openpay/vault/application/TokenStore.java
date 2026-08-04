package com.openpay.vault.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.vault.VaultProperties;
import com.openpay.vault.domain.StoredInstrument;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Where tokens live for the few minutes they are worth anything.
 *
 * <p>Redis rather than PostgreSQL, for one reason that decides it: a token must stop existing on its
 * own. Redis expiry is a property of the key, so a token that is never spent disappears whether or
 * not any code remembers to delete it. A table would need a sweeper, and a sweeper that quietly
 * stopped running would turn every fifteen-minute secret ever minted into a permanent one — with
 * nothing failing to say so.
 */
@Component
public class TokenStore {

    private static final Logger log = LoggerFactory.getLogger(TokenStore.class);
    private static final String KEY_PREFIX = "vault:token:";
    private static final String TOKEN_PREFIX = "tok_";
    /** 32 bytes of randomness. A token is a bearer reference, so guessing one must be hopeless. */
    private static final int TOKEN_BYTES = 32;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final VaultProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenStore(StringRedisTemplate redis, ObjectMapper objectMapper, VaultProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public String mint(StoredInstrument instrument) {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        try {
            redis.opsForValue().set(
                    KEY_PREFIX + token, objectMapper.writeValueAsString(instrument), properties.getTokenTtl());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialise an instrument", exception);
        }
        return token;
    }

    /**
     * Spends a token, or reports that there was nothing to spend.
     *
     * <p>{@code getAndDelete} rather than a read followed by a delete, because the gap between those
     * two is exactly where a replay wins. Two concurrent redemptions of one token must not both
     * succeed, and Redis deciding which one gets the value is what makes single-use a guarantee
     * rather than a race that is usually fine.
     *
     * <p>An expired, unknown, or already-spent token is the same empty answer on purpose. Telling
     * them apart would let a caller probe which tokens have existed.
     */
    public Optional<StoredInstrument> redeem(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String json = redis.opsForValue().getAndDelete(KEY_PREFIX + token);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, StoredInstrument.class));
        } catch (JsonProcessingException exception) {
            // The token was real and is now spent, which is the safe outcome — it cannot be retried
            // into working. Logged without the token, because a token in a log is a token in a log.
            log.error("A stored instrument could not be read back and has been discarded", exception);
            return Optional.empty();
        }
    }
}
