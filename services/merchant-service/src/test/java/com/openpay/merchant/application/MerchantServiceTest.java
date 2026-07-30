package com.openpay.merchant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openpay.merchant.api.CreateMerchantRequest;
import com.openpay.merchant.api.MerchantResponse;
import com.openpay.merchant.domain.Merchant;
import com.openpay.merchant.domain.MerchantRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MerchantServiceTest {

    @Mock
    private MerchantRepository merchantRepository;

    @InjectMocks
    private MerchantService merchantService;

    @Test
    void createsMerchantWhenMerchantCodeIsUnique() {
        CreateMerchantRequest request = new CreateMerchantRequest("justpay-demo", "JustPay Demo", null, "USD");
        when(merchantRepository.findByMerchantCode("justpay-demo")).thenReturn(Optional.empty());
        when(merchantRepository.save(any(Merchant.class))).thenAnswer(invocation -> {
            Merchant merchant = invocation.getArgument(0);
            merchant.setId(UUID.randomUUID());
            return withTimestamps(merchant);
        });

        MerchantResponse response = merchantService.createMerchant(request);

        assertThat(response.merchantCode()).isEqualTo("justpay-demo");
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.defaultCurrency()).isEqualTo("USD");
        verify(merchantRepository).save(any(Merchant.class));
    }

    @Test
    void rejectsDuplicateMerchantCode() {
        Merchant existing = new Merchant();
        existing.setId(UUID.randomUUID());
        existing.setMerchantCode("justpay-demo");
        when(merchantRepository.findByMerchantCode("justpay-demo")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> merchantService.createMerchant(
                        new CreateMerchantRequest("justpay-demo", "JustPay Demo", null, "USD")))
                .isInstanceOf(MerchantAlreadyExistsException.class);
    }

    private Merchant withTimestamps(Merchant merchant) {
        try {
            var createdAt = Merchant.class.getDeclaredField("createdAt");
            var updatedAt = Merchant.class.getDeclaredField("updatedAt");
            createdAt.setAccessible(true);
            updatedAt.setAccessible(true);
            createdAt.set(merchant, OffsetDateTime.now());
            updatedAt.set(merchant, OffsetDateTime.now());
            return merchant;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
