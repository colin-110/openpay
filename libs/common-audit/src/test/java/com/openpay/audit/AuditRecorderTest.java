package com.openpay.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openpay.observability.CorrelationIdFilter;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The audit trail had no test. The two properties worth pinning down are the ones the class was
 * written for: a failed write must not take down the operation being recorded, and an
 * unauthenticated actor must still produce an entry rather than a null column.
 *
 * <p>{@code REQUIRES_NEW} is deliberately not asserted here — it is proxy behaviour, invisible to a
 * unit test that calls the bean directly, and asserting it would need a Spring context and a real
 * transaction manager. What this covers is everything inside the method.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditRecorderTest {

    @Mock
    private AuditRepository repository;

    private AuditRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new AuditRecorder(repository);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        MDC.clear();
    }

    @Test
    void recordsASuccessfulAction() {
        UUID merchantId = UUID.randomUUID();

        recorder.record(AuditAction.API_KEY_ISSUED, "ops@example.com", "key-1", merchantId, "issued a key");

        AuditEntry saved = captureSaved();
        assertThat(saved.getAction()).isEqualTo(AuditAction.API_KEY_ISSUED);
        assertThat(saved.getActor()).isEqualTo("ops@example.com");
        assertThat(saved.getSubject()).isEqualTo("key-1");
        assertThat(saved.getMerchantId()).isEqualTo(merchantId);
        assertThat(saved.isSucceeded()).isTrue();
    }

    @Test
    void recordsAFailedActionAsFailedRatherThanNotAtAll() {
        // The refused attempts are the entries most worth having; a log containing only the
        // actions that worked answers none of the questions an audit log exists for.
        recorder.recordFailure(AuditAction.LOGIN_FAILED, "attacker@example.com", null, null, "bad password");

        AuditEntry saved = captureSaved();
        assertThat(saved.isSucceeded()).isFalse();
        assertThat(saved.getAction()).isEqualTo(AuditAction.LOGIN_FAILED);
    }

    @Test
    void namesAnUnauthenticatedActorAnonymousRatherThanLeavingItNull() {
        recorder.recordFailure(AuditAction.LOGIN_FAILED, null, null, null, "no credential");

        assertThat(captureSaved().getActor()).isEqualTo("anonymous");
    }

    @Test
    void treatsABlankActorTheSameAsAMissingOne() {
        recorder.recordFailure(AuditAction.LOGIN_FAILED, "   ", null, null, "no credential");

        assertThat(captureSaved().getActor()).isEqualTo("anonymous");
    }

    @Test
    void neverLetsAFailedAuditWriteBreakTheOperationItWasRecording() {
        // An audit-table outage must not become a platform outage — nobody should be unable to
        // sign in because the record of them signing in could not be written.
        when(repository.save(any())).thenThrow(new IllegalStateException("audit table is gone"));

        assertThatCode(() -> recorder.record(AuditAction.LOGIN_SUCCEEDED, "user@example.com", null, null, "ok"))
                .doesNotThrowAnyException();
    }

    @Test
    void capturesTheCorrelationIdSoAnEntryTiesBackToItsRequestLogs() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-abc");

        recorder.record(AuditAction.LOGIN_SUCCEEDED, "user@example.com", null, null, "ok");

        assertThat(captureSaved().getCorrelationId()).isEqualTo("corr-abc");
    }

    @Test
    void recordsTheAddressThisServiceActuallySawWhenInsideARequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        // Set on purpose and expected to be ignored: this header is attacker-controlled unless a
        // proxy overwrites it, and an audit log recording a chosen value as fact is worse than one
        // recording the proxy.
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        recorder.record(AuditAction.LOGIN_SUCCEEDED, "user@example.com", null, null, "ok");

        assertThat(captureSaved().getSourceIp()).isEqualTo("203.0.113.7");
    }

    @Test
    void recordsNoAddressWhenThereIsNoRequestToTakeOneFrom() {
        // A scheduled job or a Kafka consumer has no servlet request, and inventing an address
        // for it would put a fiction in the evidence.
        recorder.record(AuditAction.MERCHANT_CREATED, "scheduler", null, null, "nightly run");

        assertThat(captureSaved().getSourceIp()).isNull();
    }

    private AuditEntry captureSaved() {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
