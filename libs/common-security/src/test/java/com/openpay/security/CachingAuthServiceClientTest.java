package com.openpay.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Caching an authentication decision is the kind of optimisation that turns into a security bug
 * without anyone noticing, so the tests that matter here are the ones about what is
 * <em>not</em> cached.
 */
@ExtendWith(MockitoExtension.class)
class CachingAuthServiceClientTest {

    private static final String API_KEY = "opk_live_abcdef123456";

    @Mock
    private AuthServiceClient delegate;

    @Test
    void asksAuthServiceOnceAndServesTheRestFromCache() {
        ApiKeyPrincipal principal = new ApiKeyPrincipal(UUID.randomUUID(), "payments:write");
        when(delegate.validateApiKey(API_KEY)).thenReturn(principal);
        CachingAuthServiceClient client = new CachingAuthServiceClient(delegate, Duration.ofSeconds(30));

        for (int i = 0; i < 50; i++) {
            assertThat(client.validateApiKey(API_KEY)).isEqualTo(principal);
        }

        // The whole point: fifty authenticated requests, one network call.
        verify(delegate, times(1)).validateApiKey(API_KEY);
    }

    @Test
    void asksAgainOnceTheEntryHasExpired() throws Exception {
        ApiKeyPrincipal principal = new ApiKeyPrincipal(UUID.randomUUID(), "payments:write");
        when(delegate.validateApiKey(API_KEY)).thenReturn(principal);
        CachingAuthServiceClient client = new CachingAuthServiceClient(delegate, Duration.ofMillis(30));

        client.validateApiKey(API_KEY);
        Thread.sleep(60);
        client.validateApiKey(API_KEY);

        // This is the revocation window closing. A key that stayed cached forever would be a key
        // that could never be revoked.
        verify(delegate, times(2)).validateApiKey(API_KEY);
    }

    @Test
    void neverCachesARejection() {
        when(delegate.validateApiKey(API_KEY)).thenThrow(new InvalidApiKeyException("API key is invalid"));
        CachingAuthServiceClient client = new CachingAuthServiceClient(delegate, Duration.ofSeconds(30));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.validateApiKey(API_KEY))
                    .isInstanceOf(InvalidApiKeyException.class);
        }

        // Caching a rejection would leave a freshly issued key broken for the whole TTL, and would
        // hide repeated bad attempts from auth-service's throttle, which needs to see them.
        verify(delegate, times(3)).validateApiKey(API_KEY);
    }

    @Test
    void neverCachesAnOutage() {
        when(delegate.validateApiKey(API_KEY))
                .thenThrow(new AuthServiceUnavailableException("Auth service is unreachable", null));
        CachingAuthServiceClient client = new CachingAuthServiceClient(delegate, Duration.ofSeconds(30));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.validateApiKey(API_KEY))
                    .isInstanceOf(AuthServiceUnavailableException.class);
        }

        // An unreachable auth-service must keep reading as an outage. Remembering the failure would
        // extend a blip into a TTL-long refusal after auth-service had already recovered.
        verify(delegate, times(3)).validateApiKey(API_KEY);
    }

    @Test
    void keepsDifferentKeysApart() {
        ApiKeyPrincipal first = new ApiKeyPrincipal(UUID.randomUUID(), "payments:write");
        ApiKeyPrincipal second = new ApiKeyPrincipal(UUID.randomUUID(), "payments:read");
        when(delegate.validateApiKey("key-one")).thenReturn(first);
        when(delegate.validateApiKey("key-two")).thenReturn(second);
        CachingAuthServiceClient client = new CachingAuthServiceClient(delegate, Duration.ofSeconds(30));

        // Two merchants sharing one cache entry would be the worst bug this class could have:
        // one merchant's payments recorded against another's account.
        assertThat(client.validateApiKey("key-one")).isEqualTo(first);
        assertThat(client.validateApiKey("key-two")).isEqualTo(second);
        assertThat(client.validateApiKey("key-one")).isEqualTo(first);
        assertThat(client.validateApiKey("key-two")).isEqualTo(second);

        verify(delegate, times(1)).validateApiKey("key-one");
        verify(delegate, times(1)).validateApiKey("key-two");
    }

    @Test
    void preservesTheAuthorityThatCameBackSoAReadOnlyKeyStaysReadOnly() {
        UUID merchantId = UUID.randomUUID();
        when(delegate.validateApiKey(API_KEY)).thenReturn(new ApiKeyPrincipal(merchantId, "payments:read"));
        CachingAuthServiceClient client = new CachingAuthServiceClient(delegate, Duration.ofSeconds(30));

        client.validateApiKey(API_KEY);
        ApiKeyPrincipal fromCache = client.validateApiKey(API_KEY);

        assertThat(fromCache.merchantId()).isEqualTo(merchantId);
        assertThat(fromCache.authority()).isEqualTo("payments:read");
    }
}
