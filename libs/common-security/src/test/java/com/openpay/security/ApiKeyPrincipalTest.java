package com.openpay.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApiKeyPrincipalTest {

    @Test
    void aWriteScopedKeyAndAnAdminSessionMayBothMoveMoney() {
        assertThat(principal("payments:write").canWrite()).isTrue();
        assertThat(principal("MERCHANT_ADMIN").canWrite()).isTrue();
    }

    @Test
    void aReadScopedKeyAndAViewerSessionMayNot() {
        assertThat(principal("payments:read").canWrite()).isFalse();
        assertThat(principal("MERCHANT_VIEWER").canWrite()).isFalse();
    }

    @Test
    void anUnrecognisedAuthorityGetsReadAccessRatherThanWrite() {
        // The allowlist is the point. A scope nobody has taught this class about is a reason to be
        // careful, not a reason to assume the best — which is how "payments:write-ish" typos, and
        // scopes invented by a future version, fail safe.
        assertThat(principal("payments:everything").canWrite()).isFalse();
        assertThat(principal("").canWrite()).isFalse();
        assertThat(principal(null).canWrite()).isFalse();
    }

    @Test
    void authorityComparisonIsExactNotSubstring() {
        // "payments:write" appearing inside a longer string must not grant write.
        assertThat(principal("no-payments:write-here").canWrite()).isFalse();
    }

    @Test
    void requireWriteNamesTheActionItRefused() {
        assertThatThrownBy(() -> principal("MERCHANT_VIEWER").requireWrite("issue refunds"))
                .isInstanceOf(InsufficientAuthorityException.class)
                .hasMessageContaining("issue refunds");
    }

    @Test
    void requireWritePassesQuietlyForAnAuthorisedCaller() {
        principal("MERCHANT_ADMIN").requireWrite("issue refunds");
    }

    private ApiKeyPrincipal principal(String authority) {
        return new ApiKeyPrincipal(UUID.randomUUID(), authority);
    }
}
