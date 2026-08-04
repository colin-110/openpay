package com.openpay.storefront;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a basket costs is the server's business.
 *
 * <p>The tests here are about one attack that is older than online payments and still works on
 * plenty of sites: the customer edits the total in the request. It costs nothing to get right and
 * everything to get wrong, and it is the reason the shop takes product ids rather than an amount.
 */
@ExtendWith(MockitoExtension.class)
class CatalogPricingTest {

    private final Catalog catalog = new Catalog();

    @Mock
    private GatewayClient gateway;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        StorefrontProperties properties = new StorefrontProperties();
        properties.setApiKey("opk_live_demo_key");
        properties.setPublishableKey("opk_pub_demo_key");
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CheckoutController(gateway, properties, catalog))
                .build();
    }

    @Test
    void totalsACartFromTheShopsOwnPrices() {
        // One kettle at 240.00 and two sets of cups at 1180.00 each.
        long total = catalog.total(List.of(
                new Catalog.CartItem("kettle", 1),
                new Catalog.CartItem("cups", 2)));

        assertThat(total).isEqualTo(240_00L + 2 * 1_180_00L);
    }

    @Test
    void refusesACartNamingSomethingTheShopDoesNotSell() {
        // Not "charge for the part we recognised". A basket that cannot be priced in full is one
        // that must not be charged for at all.
        assertThatThrownBy(() -> catalog.total(List.of(
                new Catalog.CartItem("kettle", 1),
                new Catalog.CartItem("a-free-kettle", 1))))
                .isInstanceOf(Catalog.UnknownProductException.class);
    }

    @Test
    void ignoresAnAmountSentAlongsideACart() throws Exception {
        when(gateway.createPayment(anyLong(), anyString(), anyString(), any()))
                .thenReturn(Map.of("id", UUID.randomUUID().toString(), "status", "CREATED"));

        // The oldest trick there is: a real cart, and a total of one rupee next to it. The cart
        // wins, and the customer is charged 240.00 for the kettle they actually put in it.
        mockMvc.perform(post("/api/checkout")
                        .contentType("application/json")
                        .content("{\"items\":[{\"productId\":\"kettle\",\"quantity\":1}],"
                                + "\"amount\":100,\"currency\":\"INR\"}"))
                .andExpect(status().isOk());

        verify(gateway).createPayment(eq(240_00L), eq("INR"), anyString(), any());
    }

    @Test
    void stillHonoursABareAmountWhenThereIsNoCart() throws Exception {
        when(gateway.createPayment(anyLong(), anyString(), anyString(), any()))
                .thenReturn(Map.of("id", UUID.randomUUID().toString(), "status", "CREATED"));

        // scripts/demo-payment.sh and the acceptance suite drive this shop without a basket: they
        // care that a payment happens, not what was bought. Breaking them would be a silly cost.
        mockMvc.perform(post("/api/checkout")
                        .contentType("application/json")
                        .content("{\"amount\":24000,\"currency\":\"INR\"}"))
                .andExpect(status().isOk());

        verify(gateway).createPayment(eq(24_000L), eq("INR"), anyString(), any());
    }

    @Test
    void refusesAnEmptyBasketRatherThanTakingAZeroPayment() throws Exception {
        mockMvc.perform(post("/api/checkout")
                        .contentType("application/json")
                        .content("{\"items\":[],\"amount\":0,\"currency\":\"INR\"}"))
                .andExpect(status().isBadRequest());

        verify(gateway, never()).createPayment(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void refusesAQuantityBeyondWhatTheShopAccepts() throws Exception {
        // Bounded at the request edge, so no arithmetic below it has to worry about overflow.
        mockMvc.perform(post("/api/checkout")
                        .contentType("application/json")
                        .content("{\"items\":[{\"productId\":\"kettle\",\"quantity\":1000}],\"currency\":\"INR\"}"))
                .andExpect(status().isBadRequest());

        verify(gateway, never()).createPayment(anyLong(), anyString(), anyString(), any());
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
