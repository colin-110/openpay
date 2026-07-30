package com.openpay.mockbank;

import static org.assertj.core.api.Assertions.assertThat;

import com.openpay.mockbank.domain.BankProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "openpay.bank.name=test-bank")
class MockBankApplicationTests {

    @Autowired
    private BankProperties properties;

    @Test
    void bindsBankConfiguration() {
        assertThat(properties.getName()).isEqualTo("test-bank");
        assertThat(properties.getDeclineRate()).isZero();
    }
}
