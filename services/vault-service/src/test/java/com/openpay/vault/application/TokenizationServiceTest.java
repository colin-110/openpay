package com.openpay.vault.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.openpay.vault.VaultProperties;
import com.openpay.vault.api.TokenResponse;
import com.openpay.vault.api.TokenizeRequest;
import com.openpay.vault.domain.StoredInstrument;
import java.time.YearMonth;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The service that turns a card into a token, tested mostly for what it refuses and for what it
 * declines to say while refusing.
 *
 * <p>The card numbers here are published test numbers. None is a real card.
 */
@ExtendWith(MockitoExtension.class)
class TokenizationServiceTest {

    private static final String VISA = "4242424242424242";
    private static final UUID MERCHANT = UUID.randomUUID();

    @Mock
    private TokenStore tokenStore;

    private TokenizationService service;

    @BeforeEach
    void setUp() {
        service = new TokenizationService(tokenStore, new VaultProperties());
    }

    private TokenizeRequest card(String number, Integer month, Integer year, String cvc) {
        return new TokenizeRequest("card", number, month, year, cvc, null);
    }

    private TokenizeRequest validCard() {
        YearMonth nextYear = YearMonth.now().plusYears(1);
        return card(VISA, nextYear.getMonthValue(), nextYear.getYear(), "123");
    }

    @Test
    void keepsOnlyTheNetworkAndTheLastFour() {
        when(tokenStore.mint(any())).thenReturn("tok_abc");

        TokenResponse response = service.tokenize(validCard(), MERCHANT);

        ArgumentCaptor<StoredInstrument> stored = ArgumentCaptor.forClass(StoredInstrument.class);
        verify(tokenStore).mint(stored.capture());

        // The whole design in one assertion: what gets stored is a network and four digits, and
        // there is nowhere in StoredInstrument for the rest of the number to go.
        assertThat(stored.getValue().network()).isEqualTo("visa");
        assertThat(stored.getValue().last4()).isEqualTo("4242");
        assertThat(stored.getValue().type()).isEqualTo("card");
        assertThat(response.token()).isEqualTo("tok_abc");
        assertThat(response.last4()).isEqualTo("4242");
    }

    @Test
    void acceptsACardNumberTypedWithSpaces() {
        when(tokenStore.mint(any())).thenReturn("tok_abc");
        YearMonth nextYear = YearMonth.now().plusYears(1);

        // How a human types it off the card in front of them. Refusing this would be a checkout
        // that fails for a reason the customer cannot see.
        service.tokenize(card("4242 4242 4242 4242", nextYear.getMonthValue(), nextYear.getYear(), "123"), MERCHANT);

        verify(tokenStore).mint(any());
    }

    @Test
    void neverPutsTheCardNumberInAnErrorMessage() {
        // The property this whole service exists for. A validation message is the likeliest place
        // for a PAN to escape into a log or a bug report, because it is written to be helpful.
        assertThatThrownBy(() -> service.tokenize(card("4242424242424241", 12, 2099, "123"), MERCHANT))
                .isInstanceOf(InvalidInstrumentException.class)
                .extracting(Throwable::getMessage, InstanceOfAssertFactories.STRING)
                .doesNotContain("4242")
                .doesNotContain("424242424242424");

        verifyNoInteractions(tokenStore);
    }

    @Test
    void refusesACardThatFailsItsCheckDigit() {
        assertThatThrownBy(() -> service.tokenize(card("4242424242424241", 12, 2099, "123"), MERCHANT))
                .isInstanceOf(InvalidInstrumentException.class)
                .extracting(e -> ((InvalidInstrumentException) e).getField())
                .isEqualTo("number");
    }

    @Test
    void refusesAnExpiredCard() {
        YearMonth lastMonth = YearMonth.now().minusMonths(1);

        assertThatThrownBy(() ->
                service.tokenize(card(VISA, lastMonth.getMonthValue(), lastMonth.getYear(), "123"), MERCHANT))
                .isInstanceOf(InvalidInstrumentException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void acceptsACardInItsExpiryMonth() {
        when(tokenStore.mint(any())).thenReturn("tok_abc");
        YearMonth thisMonth = YearMonth.now();

        // A card is good through the last day of the month printed on it. Refusing on the first of
        // that month would decline a valid card for a whole month, every month.
        service.tokenize(card(VISA, thisMonth.getMonthValue(), thisMonth.getYear(), "123"), MERCHANT);

        verify(tokenStore).mint(any());
    }

    @Test
    void acceptsATwoDigitExpiryYear() {
        when(tokenStore.mint(any())).thenReturn("tok_abc");
        YearMonth nextYear = YearMonth.now().plusYears(1);

        // What is actually printed on the card, and what people therefore type.
        service.tokenize(card(VISA, nextYear.getMonthValue(), nextYear.getYear() % 100, "123"), MERCHANT);

        verify(tokenStore).mint(any());
    }

    @Test
    void asksAmexForFourSecurityDigits() {
        YearMonth nextYear = YearMonth.now().plusYears(1);

        assertThatThrownBy(() -> service.tokenize(
                card("378282246310005", nextYear.getMonthValue(), nextYear.getYear(), "123"), MERCHANT))
                .isInstanceOf(InvalidInstrumentException.class)
                .extracting(e -> ((InvalidInstrumentException) e).getField())
                .isEqualTo("securityCode");
    }

    @Test
    void refusesAnUnsupportedNetworkBeforeBlamingTheCheckDigit() {
        // Ordering matters for the message the customer sees: "we do not take this card" is
        // actionable, "that number is not valid" for a card they copied correctly is not.
        assertThatThrownBy(() -> service.tokenize(card("9999999999999995", 12, 2099, "123"), MERCHANT))
                .isInstanceOf(InvalidInstrumentException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    void tokenisesAUpiAddressAndKeepsTheBankHandle() {
        when(tokenStore.mint(any())).thenReturn("tok_upi");

        TokenResponse response = service.tokenize(
                new TokenizeRequest("upi", null, null, null, null, "customer@okhdfcbank"), MERCHANT);

        ArgumentCaptor<StoredInstrument> stored = ArgumentCaptor.forClass(StoredInstrument.class);
        verify(tokenStore).mint(stored.capture());
        assertThat(stored.getValue().type()).isEqualTo("upi");
        assertThat(stored.getValue().bank()).isEqualTo("okhdfcbank");
        assertThat(response.token()).isEqualTo("tok_upi");
    }

    @Test
    void refusesSomethingThatIsNotAUpiAddress() {
        assertThatThrownBy(() -> service.tokenize(
                new TokenizeRequest("upi", null, null, null, null, "not-an-address"), MERCHANT))
                .isInstanceOf(InvalidInstrumentException.class)
                .extracting(e -> ((InvalidInstrumentException) e).getField())
                .isEqualTo("vpa");
    }
}
