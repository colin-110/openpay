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
class RoutingServiceTest {

    private static final UUID PAYMENT_ID = UUID.randomUUID();
    private static final UUID MERCHANT_ID = UUID.randomUUID();

    @Mock
    private ProviderTransactionRepository transactionRepository;

    @Mock
    private ProviderClient providerClient;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private RoutingService routingService;

    @BeforeEach
    void setUp() {
        when(transactionRepository.saveAndFlush(any(ProviderTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.existsByPaymentId(any(UUID.class))).thenReturn(false);
        routingService = new RoutingService(
                properties(), transactionRepository, providerClient, kafkaTemplate, new EventCodec());
    }

    @Test
    void sendsToTheHighestPriorityProvider() {
        when(providerClient.dispatch(eq("bank-a"), anyString(), any(), anyLong(), anyString()))
                .thenReturn("bank-a-ref");

        routingService.route(PAYMENT_ID, MERCHANT_ID, 10_000L, "USD", "corr-1");

        verify(providerClient).dispatch(eq("bank-a"), anyString(), any(), anyLong(), anyString());
        verify(providerClient, never()).dispatch(eq("bank-b"), anyString(), any(), anyLong(), anyString());
        assertThat(publishedTopics()).contains(OpenPayTopics.PAYMENT_PROVIDER_DISPATCHED);
    }

    @Test
    void failsOverToTheNextProviderWhenTheFirstRefuses() {
        when(providerClient.dispatch(eq("bank-a"), anyString(), any(), anyLong(), anyString()))
                .thenThrow(new ProviderUnavailableException("bank-a down", null));
        when(providerClient.dispatch(eq("bank-b"), anyString(), any(), anyLong(), anyString()))
                .thenReturn("bank-b-ref");

        routingService.route(PAYMENT_ID, MERCHANT_ID, 10_000L, "USD", "corr-1");

        verify(providerClient).dispatch(eq("bank-b"), anyString(), any(), anyLong(), anyString());
        assertThat(publishedTopics()).contains(OpenPayTopics.PAYMENT_PROVIDER_DISPATCHED);
    }

    @Test
    void bothAttemptsAreRecordedWhenTheFirstProviderFails() {
        when(providerClient.dispatch(eq("bank-a"), anyString(), any(), anyLong(), anyString()))
                .thenThrow(new ProviderUnavailableException("bank-a down", null));
        when(providerClient.dispatch(eq("bank-b"), anyString(), any(), anyLong(), anyString()))
                .thenReturn("bank-b-ref");

        routingService.route(PAYMENT_ID, MERCHANT_ID, 10_000L, "USD", "corr-1");

        ArgumentCaptor<ProviderTransaction> captor = ArgumentCaptor.forClass(ProviderTransaction.class);
        verify(transactionRepository, org.mockito.Mockito.times(2)).saveAndFlush(captor.capture());
        assertThat(captor.getAllValues()).extracting(ProviderTransaction::getProviderName)
                .containsExactly("bank-a", "bank-b");
        assertThat(captor.getAllValues()).extracting(ProviderTransaction::getAttemptNo)
                .containsExactly(1, 2);
    }

    @Test
    void failsThePaymentWhenEveryProviderIsExhausted() {
        when(providerClient.dispatch(anyString(), anyString(), any(), anyLong(), anyString()))
                .thenThrow(new ProviderUnavailableException("down", null));

        routingService.route(PAYMENT_ID, MERCHANT_ID, 10_000L, "USD", "corr-1");

        // Running out of providers has to be an outcome, or the payment hangs in limbo forever.
        assertThat(publishedTopics()).contains(OpenPayTopics.PROVIDER_CALLBACK_RECEIVED);
    }

    @Test
    void stopsCallingAProviderOnceItsBreakerOpens() {
        when(providerClient.dispatch(anyString(), anyString(), any(), anyLong(), anyString()))
                .thenThrow(new ProviderUnavailableException("down", null));

        // Threshold is 2 in the test config, so three payments is more than enough to open both.
        for (int i = 0; i < 3; i++) {
            routingService.route(UUID.randomUUID(), MERCHANT_ID, 10_000L, "USD", "corr-" + i);
        }

        assertThat(routingService.breakerStates().values())
                .allMatch(state -> state == CircuitBreaker.State.OPEN);
    }

    @Test
    void ignoresARedeliveredPaymentRatherThanChargingTwice() {
        when(transactionRepository.existsByPaymentId(PAYMENT_ID)).thenReturn(true);

        routingService.route(PAYMENT_ID, MERCHANT_ID, 10_000L, "USD", "corr-1");

        verify(providerClient, never()).dispatch(anyString(), anyString(), any(), anyLong(), anyString());
    }

    private List<String> publishedTopics() {
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, org.mockito.Mockito.atLeastOnce())
                .send(topicCaptor.capture(), anyString(), anyString());
        return topicCaptor.getAllValues();
    }

    private RouterProperties properties() {
        RouterProperties properties = new RouterProperties();
        properties.setFailureThreshold(2);
        properties.setBreakerOpenDuration(Duration.ofSeconds(30));

        RouterProperties.Provider a = new RouterProperties.Provider();
        a.setName("bank-a");
        a.setBaseUrl("http://bank-a.test");
        a.setPriority(10);

        RouterProperties.Provider b = new RouterProperties.Provider();
        b.setName("bank-b");
        b.setBaseUrl("http://bank-b.test");
        b.setPriority(20);

        properties.setProviders(List.of(a, b));
        return properties;
    }
}
