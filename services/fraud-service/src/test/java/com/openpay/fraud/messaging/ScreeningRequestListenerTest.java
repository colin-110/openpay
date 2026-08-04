package com.openpay.fraud.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.openpay.events.EventCodec;
import com.openpay.events.EventEnvelope;
import com.openpay.events.OpenPayTopics;
import com.openpay.events.payload.FraudCheckRequested;
import com.openpay.fraud.application.FraudService;
import com.openpay.fraud.application.ScreeningRequest;
import com.openpay.observability.CorrelationIdFilter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

/**
 * The consumer that only exists in asynchronous mode, and which nothing exercised until now.
 *
 * <p>Worth its own tests rather than being folded into the service's, because the interesting
 * behaviour is all at the edge: what it does with a redelivery, and what it does with a failure.
 * Kafka delivery is at-least-once and the error handler retries before dead-lettering, so both are
 * routine rather than exceptional.
 */
@ExtendWith(MockitoExtension.class)
class ScreeningRequestListenerTest {

    @Mock
    private FraudService fraudService;

    private final EventCodec eventCodec = new EventCodec();

    @Test
    void screensThePaymentTheEventNames() {
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        ScreeningRequestListener listener = new ScreeningRequestListener(fraudService, eventCodec);

        listener.onScreeningRequested(encode(paymentId, merchantId, 25_000L, "INR"));

        ArgumentCaptor<ScreeningRequest> captor = ArgumentCaptor.forClass(ScreeningRequest.class);
        verify(fraudService).screen(captor.capture());
        assertThat(captor.getValue().paymentId()).isEqualTo(paymentId);
        assertThat(captor.getValue().merchantId()).isEqualTo(merchantId);
        assertThat(captor.getValue().amount()).isEqualTo(25_000L);
        assertThat(captor.getValue().currency()).isEqualTo("INR");
    }

    @Test
    void aRedeliveryScreensAgainAndLetsTheServiceDeduplicate() {
        UUID paymentId = UUID.randomUUID();
        String message = encode(paymentId, UUID.randomUUID(), 25_000L, "INR");
        ScreeningRequestListener listener = new ScreeningRequestListener(fraudService, eventCodec);

        listener.onScreeningRequested(message);
        listener.onScreeningRequested(message);

        // Deliberately not deduplicated here. FraudService.screen is idempotent on payment id and
        // returns the stored decision, which is the stronger guarantee: the velocity window moves
        // between deliveries, so a listener that re-screened "properly" could reach a different
        // verdict for the same payment. One wasted query is the correct price for that.
        verify(fraudService, times(2)).screen(any(ScreeningRequest.class));
    }

    @Test
    void letsAFailurePropagateSoTheEventIsRetriedRatherThanLost() {
        doThrow(new IllegalStateException("fraud database is down"))
                .when(fraudService).screen(any(ScreeningRequest.class));
        ScreeningRequestListener listener = new ScreeningRequestListener(fraudService, eventCodec);
        String message = encode(UUID.randomUUID(), UUID.randomUUID(), 25_000L, "INR");

        // Swallowing this would acknowledge the offset and drop the payment on the floor: it would
        // stay HELD in payment-service with nothing left to release it. Throwing hands it to the
        // configured error handler, which retries and then dead-letters where it can be replayed.
        assertThatThrownBy(() -> listener.onScreeningRequested(message))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void doesNotLeakACorrelationIdIntoTheNextMessageOnThisThread() {
        doThrow(new IllegalStateException("fraud database is down"))
                .when(fraudService).screen(any(ScreeningRequest.class));
        ScreeningRequestListener listener = new ScreeningRequestListener(fraudService, eventCodec);

        assertThatThrownBy(() -> listener.onScreeningRequested(
                encode(UUID.randomUUID(), UUID.randomUUID(), 25_000L, "INR")))
                .isInstanceOf(IllegalStateException.class);

        // Consumer threads are pooled and long-lived. A correlation id left in the MDC by a failed
        // message would be stamped onto every log line of whatever unrelated payment that thread
        // handled next, which is worse than having no correlation id at all.
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    private String encode(UUID paymentId, UUID merchantId, long amount, String currency) {
        return eventCodec.encode(EventEnvelope.of(
                OpenPayTopics.FRAUD_CHECK_REQUESTED,
                paymentId.toString(),
                UUID.randomUUID().toString(),
                new FraudCheckRequested(paymentId, merchantId, amount, currency, null)));
    }
}
