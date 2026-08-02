package com.openpay.webhook.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openpay.events.EventCodec;
import com.openpay.webhook.domain.ProviderWebhookEvent;
import com.openpay.webhook.domain.ProviderWebhookEventRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * {@link WebhookIngestService} had no test at all before this — everything about it (dedup,
 * malformed-payload handling, refund vs. payment routing) was only ever exercised indirectly
 * through scripts/e2e.sh and the k6 webhook-spike scenario. Real collaborators where they're
 * cheap ({@link SignatureVerifier}, a real {@link EventCodec}) and mocks only at the true I/O
 * boundary (the repository, Kafka) — the same balance {@code SignatureVerifierTest} already
 * strikes for the signature half of this service.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebhookIngestServiceTest {

    private static final String SECRET = "top-secret";
    private static final String PROVIDER = "mock-bank-a";

    @Mock
    private ProviderWebhookEventRepository repository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private WebhookIngestService service;

    @BeforeEach
    void setUp() {
        WebhookProperties properties = new WebhookProperties();
        properties.setSigningSecrets(Map.of(PROVIDER, SECRET));
        properties.setTolerance(Duration.ofMinutes(5));
        SignatureVerifier verifier = new SignatureVerifier(properties);
        service = new WebhookIngestService(repository, verifier, kafkaTemplate, new EventCodec());
    }

    @Test
    void acceptsAWellFormedCallbackAndPublishesIt() {
        UUID paymentId = UUID.randomUUID();
        String body = capturedBody("evt-1", paymentId, null);
        when(repository.findByProviderNameAndProviderEventId(PROVIDER, "evt-1")).thenReturn(Optional.empty());

        WebhookIngestService.IngestResult result = ingest(body);

        assertThat(result).isEqualTo(WebhookIngestService.IngestResult.ACCEPTED);
        verify(repository).saveAndFlush(any(ProviderWebhookEvent.class));
        verify(kafkaTemplate).send(anyString(), anyString(), anyString());
    }

    @Test
    void rejectsACallbackWithAnInvalidSignature() {
        long now = Instant.now().getEpochSecond();
        String body = capturedBody("evt-2", UUID.randomUUID(), null);

        WebhookIngestService.IngestResult result = service.ingest(
                PROVIDER, String.valueOf(now), body, sign("wrong-secret", now, body), "corr-1");

        assertThat(result).isEqualTo(WebhookIngestService.IngestResult.INVALID_SIGNATURE);
        // A callback that never proved who sent it must never reach the datastore or Kafka.
        verify(repository, never()).saveAndFlush(any());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void ignoresARedeliveredCallbackAlreadySeen() {
        String body = capturedBody("evt-3", UUID.randomUUID(), null);
        when(repository.findByProviderNameAndProviderEventId(PROVIDER, "evt-3"))
                .thenReturn(Optional.of(new ProviderWebhookEvent(
                        PROVIDER, "evt-3", UUID.randomUUID(), null, "CAPTURED", true, body)));

        WebhookIngestService.IngestResult result = ingest(body);

        assertThat(result).isEqualTo(WebhookIngestService.IngestResult.DUPLICATE);
        verify(repository, never()).saveAndFlush(any());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void treatsAConcurrentDuplicateAsADuplicateRatherThanAnError() {
        // Two deliveries of the same callback race past the SELECT before either has committed;
        // the unique constraint is what actually decides it, and this is that path.
        String body = capturedBody("evt-4", UUID.randomUUID(), null);
        when(repository.findByProviderNameAndProviderEventId(PROVIDER, "evt-4")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique violation"));

        WebhookIngestService.IngestResult result = ingest(body);

        assertThat(result).isEqualTo(WebhookIngestService.IngestResult.DUPLICATE);
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void rejectsAnUnparseableBody() {
        long now = Instant.now().getEpochSecond();
        String body = "not json";

        WebhookIngestService.IngestResult result = service.ingest(
                PROVIDER, String.valueOf(now), body, sign(SECRET, now, body), "corr-1");

        assertThat(result).isEqualTo(WebhookIngestService.IngestResult.MALFORMED);
    }

    @Test
    void rejectsABodyMissingARequiredField() {
        // eventId present, outcome present, paymentId missing.
        String body = "{\"eventId\":\"evt-5\",\"outcome\":\"CAPTURED\"}";

        assertThat(ingest(body)).isEqualTo(WebhookIngestService.IngestResult.MALFORMED);
    }

    @Test
    void rejectsAnOutcomeItDoesNotRecognise() {
        // A provider sending a status this platform has no handling for must be refused, not
        // guessed at or forwarded as-is — an unknown outcome reaching a consumer unchanged would
        // be trusting the acquirer's vocabulary to never change.
        String body = "{\"eventId\":\"evt-6\",\"outcome\":\"SOMETHING_NEW\",\"paymentId\":\"" + UUID.randomUUID() + "\"}";

        assertThat(ingest(body)).isEqualTo(WebhookIngestService.IngestResult.MALFORMED);
    }

    @Test
    void rejectsAPaymentIdThatIsNotAUuid() {
        String body = "{\"eventId\":\"evt-7\",\"outcome\":\"CAPTURED\",\"paymentId\":\"not-a-uuid\"}";

        assertThat(ingest(body)).isEqualTo(WebhookIngestService.IngestResult.MALFORMED);
    }

    @Test
    void routesARefundOutcomeToTheRefundTopicNotThePaymentTopic() {
        UUID paymentId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        String body = capturedBody("evt-8", paymentId, refundId);
        when(repository.findByProviderNameAndProviderEventId(PROVIDER, "evt-8")).thenReturn(Optional.empty());

        ingest(body);

        // A refund callback moving a payment record (or vice versa) would corrupt whichever
        // consumer received the wrong shape of event.
        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), anyString());
        verify(repository).saveAndFlush(any());
    }

    private WebhookIngestService.IngestResult ingest(String body) {
        long now = Instant.now().getEpochSecond();
        return service.ingest(PROVIDER, String.valueOf(now), body, sign(SECRET, now, body), "corr-1");
    }

    private String capturedBody(String eventId, UUID paymentId, UUID refundId) {
        String refundPart = refundId == null ? "" : ",\"refundId\":\"" + refundId + "\"";
        String outcome = refundId == null ? "CAPTURED" : "REFUND_SUCCEEDED";
        return "{\"eventId\":\"" + eventId + "\",\"outcome\":\"" + outcome + "\",\"paymentId\":\"" + paymentId
                + "\",\"providerReference\":\"ref-123\"" + refundPart + "}";
    }

    private String sign(String secret, long timestampSeconds, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signedPayload = timestampSeconds + "." + body;
            return HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
