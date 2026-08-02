package com.openpay.mockbank.callback;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.mockbank.domain.BankProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link CallbackSender} had no test at all. It is instantiated directly here (never through a
 * Spring context), so {@code @Async} never applies — calling its methods runs them on the test
 * thread, which is exactly what a unit test wants: no timing games, no waiting on a background
 * thread that may or may not have run yet.
 *
 * <p>It really does make an HTTP call, so a real (if tiny) receiver is needed to assert against —
 * mocking {@code RestClient} would mean trusting that the mock's contract matches the real one for
 * exactly the parts this test cares about: the URL path, the signature header, and the body.
 * {@code com.sun.net.httpserver} ships with the JDK, so this needs no new test dependency.
 */
class CallbackSenderTest {

    private static final String SECRET = "mock-bank-secret";
    private static final String SIGNATURE_HEADER = "X-Provider-Signature";
    private static final String TIMESTAMP_HEADER = "X-Provider-Timestamp";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final List<CapturedRequest> received = new CopyOnWriteArrayList<>();

    private HttpServer server;
    private BankProperties properties;
    private CallbackSender sender;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/webhooks/mock-bank-a", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new CapturedRequest(
                    new String(body, StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst(SIGNATURE_HEADER),
                    exchange.getRequestHeaders().getFirst(TIMESTAMP_HEADER)));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        properties = new BankProperties();
        properties.setName("mock-bank-a");
        properties.setSigningSecret(SECRET);
        properties.setCallbackDelay(Duration.ZERO);
        properties.setCallbackUrl("http://localhost:" + server.getAddress().getPort() + "/webhooks");

        sender = new CallbackSender(properties, objectMapper);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void aSuccessfulPaymentSendsAuthorizedThenCaptured() throws Exception {
        UUID paymentId = UUID.randomUUID();

        sender.scheduleOutcome(paymentId, "provider-ref-1", false);

        assertThat(received).hasSize(2);
        assertThat(outcomeOf(received.get(0))).isEqualTo("AUTHORIZED");
        assertThat(outcomeOf(received.get(1))).isEqualTo("CAPTURED");
        assertThat(paymentIdOf(received.get(0))).isEqualTo(paymentId.toString());
        assertThat(failureReasonOf(received.get(0))).isNull();
    }

    @Test
    void aDeclinedPaymentSendsOnlyAFailedCallbackWithAReason() throws Exception {
        UUID paymentId = UUID.randomUUID();

        sender.scheduleOutcome(paymentId, "provider-ref-2", true);

        assertThat(received).hasSize(1);
        assertThat(outcomeOf(received.get(0))).isEqualTo("FAILED");
        assertThat(failureReasonOf(received.get(0))).isEqualTo("insufficient_funds");
    }

    @Test
    void aSuccessfulRefundSendsRefundSucceeded() throws Exception {
        UUID refundId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        sender.scheduleRefundOutcome(refundId, paymentId, "provider-ref-3", false);

        assertThat(received).hasSize(1);
        assertThat(outcomeOf(received.get(0))).isEqualTo("REFUND_SUCCEEDED");
        assertThat(refundIdOf(received.get(0))).isEqualTo(refundId.toString());
        assertThat(failureReasonOf(received.get(0))).isNull();
    }

    @Test
    void aRejectedRefundSendsRefundFailedWithAReason() throws Exception {
        sender.scheduleRefundOutcome(UUID.randomUUID(), UUID.randomUUID(), "provider-ref-4", true);

        assertThat(received).hasSize(1);
        assertThat(outcomeOf(received.get(0))).isEqualTo("REFUND_FAILED");
        assertThat(failureReasonOf(received.get(0))).isEqualTo("refund_rejected");
    }

    @Test
    void everyCallbackIsSignedOverItsOwnTimestampAndExactBody() throws Exception {
        // Wrong on purpose: signing the body alone would let a captured callback replay forever,
        // since it would still verify against a stale but genuine signature.
        sender.scheduleOutcome(UUID.randomUUID(), "provider-ref-5", true);

        assertThat(received).hasSize(1);
        CapturedRequest request = received.get(0);
        String expected = hmac(SECRET, request.timestamp() + "." + request.body());

        assertThat(request.signature()).isEqualToIgnoringCase(expected);
    }

    private String outcomeOf(CapturedRequest request) throws IOException {
        return field(request, "outcome");
    }

    private String failureReasonOf(CapturedRequest request) throws IOException {
        return field(request, "failureReason");
    }

    private String paymentIdOf(CapturedRequest request) throws IOException {
        return field(request, "paymentId");
    }

    private String refundIdOf(CapturedRequest request) throws IOException {
        return field(request, "refundId");
    }

    private String field(CapturedRequest request, String name) throws IOException {
        JsonNode node = objectMapper.readTree(request.body()).get(name);
        return node == null || node.isNull() ? null : node.asText();
    }

    private String hmac(String secret, String signedPayload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
    }

    private record CapturedRequest(String body, String signature, String timestamp) {
    }
}
