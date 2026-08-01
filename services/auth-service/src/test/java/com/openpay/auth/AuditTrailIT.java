package com.openpay.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openpay.audit.AuditAction;
import com.openpay.audit.AuditEntry;
import com.openpay.audit.AuditRepository;
import com.openpay.auth.api.CreateApiKeyRequest;
import com.openpay.auth.api.CreateUserRequest;
import com.openpay.auth.api.LoginRequest;
import com.openpay.auth.application.ApiKeyService;
import com.openpay.auth.application.InvalidCredentialsException;
import com.openpay.auth.application.UserService;
import com.openpay.auth.infrastructure.MerchantServiceClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "openpay.jwt.secret=an-audit-test-signing-key-of-at-least-32-bytes",
        // The limiter is disabled by giving it a budget nothing in this class can exhaust; the
        // throttle itself is covered elsewhere.
        "openpay.auth.max-failed-logins=1000",
        "openpay.auth.max-failed-logins-per-source=1000"
})
@Testcontainers
class AuditTrailIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @MockitoBean
    private MerchantServiceClient merchantServiceClient;

    @Autowired
    private UserService userService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private AuditRepository auditRepository;

    @Test
    void aRefusedLoginIsRecordedEvenThoughTheRequestFailed() {
        String email = "nobody-" + UUID.randomUUID() + "@openpay.test";

        assertThatThrownBy(() -> userService.login(new LoginRequest(email, "wrong"), "10.0.0.1"))
                .isInstanceOf(InvalidCredentialsException.class);

        // This is the entry the whole design is for. The login transaction rolled back; without a
        // separate transaction on the recorder, the record of the attempt would roll back with it
        // and the log would contain only successful sign-ins.
        AuditEntry entry = onlyEntryFor(email);
        assertThat(entry.getAction()).isEqualTo(AuditAction.LOGIN_FAILED);
        assertThat(entry.isSucceeded()).isFalse();
    }

    @Test
    void anAttemptAgainstAnAddressWithNoAccountIsStillRecorded() {
        String email = "ghost-" + UUID.randomUUID() + "@openpay.test";

        assertThatThrownBy(() -> userService.login(new LoginRequest(email, "guess"), "10.0.0.2"))
                .isInstanceOf(InvalidCredentialsException.class);

        // A burst against one address is the signal, and it is invisible if only real accounts
        // produce entries.
        assertThat(onlyEntryFor(email).getDetail()).contains("No such account");
    }

    @Test
    void aSuccessfulLoginIsRecordedWithTheMerchantItWasFor() {
        UUID merchantId = UUID.randomUUID();
        String email = "person-" + UUID.randomUUID() + "@openpay.test";
        org.mockito.Mockito.when(merchantServiceClient.merchantExists(merchantId)).thenReturn(true);
        userService.createUser(new CreateUserRequest(merchantId, email, "correct-horse", "MERCHANT_ADMIN"));

        userService.login(new LoginRequest(email, "correct-horse"), "10.0.0.3");

        // Two entries, and the actor differs between them on purpose. Creating the user was done by
        // whoever held the admin token, with the user as the subject; signing in was done by the
        // user themselves. Recording the operator as the actor of somebody else's login would
        // misattribute it.
        List<AuditEntry> forMerchant = auditRepository.findAll().stream()
                .filter(entry -> merchantId.equals(entry.getMerchantId()))
                .toList();
        assertThat(forMerchant).extracting(AuditEntry::getAction)
                .containsExactlyInAnyOrder(AuditAction.USER_CREATED, AuditAction.LOGIN_SUCCEEDED);

        AuditEntry created = entryWith(forMerchant, AuditAction.USER_CREATED);
        assertThat(created.getActor()).isEqualTo("admin-token");
        assertThat(created.getSubject()).isEqualTo(email);

        assertThat(entryWith(forMerchant, AuditAction.LOGIN_SUCCEEDED).getActor()).isEqualTo(email);
    }

    private AuditEntry entryWith(List<AuditEntry> entries, AuditAction action) {
        return entries.stream()
                .filter(entry -> entry.getAction() == action)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + action + " entry"));
    }

    @Test
    void issuingAKeyRecordsThePrefixAndNeverTheKey() {
        UUID merchantId = UUID.randomUUID();
        org.mockito.Mockito.when(merchantServiceClient.merchantExists(merchantId)).thenReturn(true);

        var issued = apiKeyService.createApiKey(
                new CreateApiKeyRequest(merchantId, "audit-test", "payments:write", null));

        AuditEntry entry = auditRepository.findAll().stream()
                .filter(candidate -> candidate.getAction() == AuditAction.API_KEY_ISSUED)
                .filter(candidate -> merchantId.equals(candidate.getMerchantId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("key issuance was not recorded"));

        assertThat(entry.getSubject()).isEqualTo(issued.keyPrefix());
        // An audit log holding usable credentials would be the softest place to steal one from.
        assertThat(entry.getSubject()).isNotEqualTo(issued.apiKey());
        assertThat(entry.getDetail()).doesNotContain(issued.apiKey());
    }

    private AuditEntry onlyEntryFor(String actor) {
        List<AuditEntry> entries = entriesFor(actor);
        assertThat(entries).hasSize(1);
        return entries.get(0);
    }

    private List<AuditEntry> entriesFor(String actor) {
        return auditRepository.findAll().stream()
                .filter(entry -> actor.equals(entry.getActor()))
                .toList();
    }
}
