package com.openpay.storefront;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.HttpClientErrorException;

@ExtendWith(MockitoExtension.class)
class CheckoutControllerTest {

    @Mock
    private GatewayClient gateway;

    private StorefrontProperties properties;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties = new StorefrontProperties();
        properties.setApiKey("opk_live_demo_key");
        properties.setPublishableKey("opk_pub_demo_key");
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CheckoutController(gateway, properties, new Catalog()))
                .build();
    }

    @Test
    void takesAPaymentAndReturnsItToThePage() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(gateway.createPayment(anyLong(), anyString(), anyString(), any()))
                .thenReturn(Map.of("id", paymentId.toString(), "status", "CREATED"));

        mockMvc.perform(post("/api/checkout")
                        .contentType("application/json")
                        .content("{\"amount\":24000,\"currency\":\"INR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.id").value(paymentId.toString()));
    }

    @Test
    void neverPutsTheApiKeyAnywhereTheBrowserCanSeeIt() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(gateway.createPayment(anyLong(), anyString(), anyString(), any()))
                .thenReturn(Map.of("id", paymentId.toString(), "status", "CREATED"));

        String payResponse = mockMvc.perform(post("/api/checkout")
                        .contentType("application/json")
                        .content("{\"amount\":24000,\"currency\":\"INR\"}"))
                .andReturn().getResponse().getContentAsString();
        String configResponse = mockMvc.perform(get("/api/checkout/config"))
                .andReturn().getResponse().getContentAsString();

        // The one rule this service has. A key reaching the page would let any visitor create and
        // refund payments against this merchant, which is the exact thing the platform's whole
        // credential design exists to prevent — and it would be invisible until someone looked.
        org.assertj.core.api.Assertions.assertThat(payResponse).doesNotContain("opk_live_demo_key");
        org.assertj.core.api.Assertions.assertThat(configResponse).doesNotContain("opk_live_demo_key");
    }

    @Test
    void honoursAnIdempotencyKeyTheClientSuppliesSoARetryIsNotASecondCharge() throws Exception {
        when(gateway.createPayment(anyLong(), anyString(), anyString(), any()))
                .thenReturn(Map.of("id", UUID.randomUUID().toString(), "status", "CREATED"));

        mockMvc.perform(post("/api/checkout")
                        .contentType("application/json")
                        .content("{\"amount\":24000,\"currency\":\"INR\",\"idempotencyKey\":\"order-42\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(gateway).createPayment(eq(24000L), eq("INR"), key.capture(), any());
        org.assertj.core.api.Assertions.assertThat(key.getValue()).isEqualTo("order-42");
    }

    @Test
    void generatesAnIdempotencyKeyWhenTheClientDoesNotSupplyOne() throws Exception {
        when(gateway.createPayment(anyLong(), anyString(), anyString(), any()))
                .thenReturn(Map.of("id", UUID.randomUUID().toString(), "status", "CREATED"));

        mockMvc.perform(post("/api/checkout")
                        .contentType("application/json")
                        .content("{\"amount\":24000,\"currency\":\"INR\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(gateway).createPayment(anyLong(), anyString(), key.capture(), any());
        // Never empty: the gateway requires the header, and an absent one would make every
        // double-click a second charge.
        org.assertj.core.api.Assertions.assertThat(key.getValue()).isNotBlank();
    }

    @Test
    void passesAriskRefusalStraightThroughSoThePageCanSayWhatHappened() throws Exception {
        when(gateway.createPayment(anyLong(), anyString(), anyString(), any()))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatusCode.valueOf(422), "Unprocessable", null,
                        "{\"message\":\"Refused on rule 'extreme-value-payment'\"}".getBytes(), null));

        mockMvc.perform(post("/api/checkout")
                        .contentType("application/json")
                        .content("{\"amount\":24000,\"currency\":\"INR\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("extreme-value-payment")));
    }

    @Test
    void refusesToPretendItCanTakeAPaymentWithNoApiKey() throws Exception {
        properties.setApiKey("");

        mockMvc.perform(post("/api/checkout")
                        .contentType("application/json")
                        .content("{\"amount\":24000,\"currency\":\"INR\"}"))
                .andExpect(status().isServiceUnavailable());

        // Better a clear message than a 401 from the gateway that reads like the platform is broken.
        verify(gateway, never()).createPayment(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void rejectsAnAmountOutsideWhatTheDemoAllows() throws Exception {
        mockMvc.perform(post("/api/checkout")
                        .contentType("application/json")
                        .content("{\"amount\":0,\"currency\":\"INR\"}"))
                .andExpect(status().isBadRequest());

        verify(gateway, never()).createPayment(anyLong(), any(), any(), any());
    }

    @Test
    void reportsAnUnknownPaymentAsNotFound() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(gateway.getPayment(unknown)).thenReturn(null);

        mockMvc.perform(get("/api/checkout/" + unknown)).andExpect(status().isNotFound());
    }

    @Test
    void tellsThePageWhetherTheShopIsUsable() throws Exception {
        mockMvc.perform(get("/api/checkout/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true));

        properties.setApiKey("");
        mockMvc.perform(get("/api/checkout/config"))
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void statusIsReadThroughTheShopsOwnCredentialSoOnlyItsPaymentsAreVisible() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(gateway.getPayment(paymentId))
                .thenReturn(Map.of("id", paymentId.toString(), "status", "CAPTURED"));

        mockMvc.perform(get("/api/checkout/" + paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));

        // The gateway scopes reads to the credential's merchant, so a payment id belonging to
        // another merchant reads as not-found rather than as someone else's money.
        verify(gateway).getPayment(paymentId);
    }

}
