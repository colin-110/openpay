package com.openpay.router.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openpay.events.EventCodec;
import com.openpay.events.OpenPayTopics;
import com.openpay.router.domain.ProviderTransaction;
import com.openpay.router.domain.ProviderTransactionRepository;
import com.openpay.router.infrastructure.ProviderClient;
import com.openpay.router.infrastructure.ProviderUnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefundRoutingServiceTest {

    private static final UUID REFUND_ID = UUID.randomUUID();
    private static final UUID PAYMENT_ID = UUID.randomUUID();
    private static final UUID MERCHANT_ID = UUID.randomUUID();

    @Mock
    private ProviderTransactionRepository transactionRepository;

    @Mock
    private ProviderClient providerClient;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private RoutingRuleService routingRuleService;

    private RefundRoutingService service;

    @BeforeEach
    void setUp() {
        when(routingRuleService.baseUrlFor("bank-a")).thenReturn(Optional.of("http://bank-a.test"));
        when(routingRuleService.baseUrlFor("bank-b")).thenReturn(Optional.of("http://bank-b.test"));
        when(routingRuleService.baseUrlFor("bank-retired")).thenReturn(Optional.empty());
        service = new RefundRoutingService(
                routingRuleService, transactionRepository, providerClient, kafkaTemplate, new EventCodec());
    }

    @Test
    void refundsStillReachAnAcquirerThatHasBeenTakenOutOfRotation() {
        // baseUrlFor resolves disabled rules too. Disabling a rule stops new payments going to an
        // acquirer; it must not strand every refund against the payments it already took.
        acceptedOn("bank-a", "bank-a-ref-1");

        service.routeRefund(REFUND_ID, PAYMENT_ID, 5_000, "USD", "corr-1");

        verify(providerClient).dispatchRefund(
                eq("bank-a"), eq("http://bank-a.test"), eq(REFUND_ID), eq(PAYMENT_ID),
                eq(5_000L), eq("USD"), eq("bank-a-ref-1"));
    }

    @Test
    void sendsTheRefundBackToTheAcquirerThatTookThePayment() {
        // The payment failed over to bank-b, so the refund must go to bank-b, not the first choice.
        acceptedOn("bank-b", "bank-b-ref-1");

        service.routeRefund(REFUND_ID, PAYMENT_ID, 5_000, "USD", "corr-1");

        verify(providerClient).dispatchRefund(
                eq("bank-b"), anyString(), eq(REFUND_ID), eq(PAYMENT_ID), eq(5_000L), eq("USD"),
                eq("bank-b-ref-1"));
        verify(providerClient, never()).dispatchRefund(
                eq("bank-a"), anyString(), any(), any(), anyLong(), anyString(), anyString());
    }

    @Test
    void failsWhenNoProviderEverAcceptedThePayment() {
        when(transactionRepository.findFirstByPaymentIdAndStatusOrderByAttemptNoDesc(
                eq(PAYMENT_ID), eq("ACCEPTED"))).thenReturn(Optional.empty());

        service.routeRefund(REFUND_ID, PAYMENT_ID, 5_000, "USD", "corr-1");

        // Nothing was captured, so there is nothing to reverse; the refund must not hang.
        verify(providerClient, never()).dispatchRefund(
                anyString(), anyString(), any(), any(), anyLong(), anyString(), anyString());
        assertThat(publishedTopic()).isEqualTo(OpenPayTopics.REFUND_CALLBACK_RECEIVED);
    }

    @Test
    void failsWhenTheOriginalAcquirerIsNotInTheRoutingTableAtAll() {
        acceptedOn("bank-retired", "old-ref");

        service.routeRefund(REFUND_ID, PAYMENT_ID, 5_000, "USD", "corr-1");

        // Failing over is not an option for a refund, so it fails loudly rather than going astray.
        verify(providerClient, never()).dispatchRefund(
                anyString(), anyString(), any(), any(), anyLong(), anyString(), anyString());
        assertThat(publishedTopic()).isEqualTo(OpenPayTopics.REFUND_CALLBACK_RECEIVED);
    }

    @Test
    void reportsAFailureWhenTheAcquirerRefusesTheRefund() {
        acceptedOn("bank-a", "bank-a-ref-1");
        org.mockito.Mockito.doThrow(new ProviderUnavailableException("bank-a down", null))
                .when(providerClient).dispatchRefund(
                        anyString(), anyString(), any(), any(), anyLong(), anyString(), anyString());

        service.routeRefund(REFUND_ID, PAYMENT_ID, 5_000, "USD", "corr-1");

        assertThat(publishedTopic()).isEqualTo(OpenPayTopics.REFUND_CALLBACK_RECEIVED);
    }

    private void acceptedOn(String provider, String reference) {
        ProviderTransaction transaction =
                new ProviderTransaction(PAYMENT_ID, MERCHANT_ID, provider, 1, 5_000, "USD");
        transaction.markAccepted(reference);
        when(transactionRepository.findFirstByPaymentIdAndStatusOrderByAttemptNoDesc(
                eq(PAYMENT_ID), eq("ACCEPTED"))).thenReturn(Optional.of(transaction));
    }

    private String publishedTopic() {
        ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topic.capture(), anyString(), anyString());
        return topic.getValue();
    }

}
