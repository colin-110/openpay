package com.openpay.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openpay.merchant.api.CreateMerchantRequest;
import com.openpay.merchant.api.MerchantResponse;
import com.openpay.merchant.application.MerchantAlreadyExistsException;
import com.openpay.merchant.application.MerchantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the real schema so Hibernate's {@code validate} actually runs against the Flyway
 * migrations. The {@code CHAR(3)} vs varchar mismatch on {@code default_currency} was exactly this
 * class of defect and reached runtime because nothing in the suite ever touched a database.
 */
@SpringBootTest
@Testcontainers
class MerchantPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private MerchantService merchantService;

    @Test
    void persistsAndReadsBackAMerchant() {
        MerchantResponse created = merchantService.createMerchant(
                new CreateMerchantRequest("it-merchant-1", "IT Merchant", "https://example.test/hook", "USD"));

        MerchantResponse fetched = merchantService.getMerchant(created.id());

        assertThat(fetched.merchantCode()).isEqualTo("it-merchant-1");
        assertThat(fetched.status()).isEqualTo("ACTIVE");
        // CHAR(3) is blank-padded by Postgres; the value must still come back usable.
        assertThat(fetched.defaultCurrency()).isEqualTo("USD");
        assertThat(fetched.createdAt()).isNotNull();
    }

    @Test
    void rejectsADuplicateMerchantCode() {
        merchantService.createMerchant(
                new CreateMerchantRequest("it-merchant-dup", "First", null, "EUR"));

        assertThatThrownBy(() -> merchantService.createMerchant(
                new CreateMerchantRequest("it-merchant-dup", "Second", null, "EUR")))
                .isInstanceOf(MerchantAlreadyExistsException.class);
    }

    @Test
    void listsMerchantsNewestFirst() {
        merchantService.createMerchant(new CreateMerchantRequest("it-list-a", "A", null, "USD"));
        merchantService.createMerchant(new CreateMerchantRequest("it-list-b", "B", null, "USD"));

        var page = merchantService.listMerchants(PageRequest.of(0, 50));

        assertThat(page.totalItems()).isGreaterThanOrEqualTo(2);
    }
}
