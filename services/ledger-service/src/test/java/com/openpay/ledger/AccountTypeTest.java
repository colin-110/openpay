package com.openpay.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.openpay.ledger.domain.AccountType;
import com.openpay.ledger.domain.EntryDirection;
import org.junit.jupiter.api.Test;

class AccountTypeTest {

    @Test
    void assetsGrowOnDebits() {
        assertThat(AccountType.ASSET.increasingDirection()).isEqualTo(EntryDirection.DEBIT);
        assertThat(AccountType.ASSET.normalise(1000, 300)).isEqualTo(700);
    }

    @Test
    void liabilitiesGrowOnCredits() {
        assertThat(AccountType.LIABILITY.increasingDirection()).isEqualTo(EntryDirection.CREDIT);
        assertThat(AccountType.LIABILITY.normalise(300, 1000)).isEqualTo(700);
    }

    @Test
    void anOverdrawnAccountReadsNegativeRatherThanWrapping() {
        // Signed on purpose: a negative balance is a real state that must be visible, not hidden
        // behind an absolute value.
        assertThat(AccountType.ASSET.normalise(100, 500)).isEqualTo(-400);
    }
}
