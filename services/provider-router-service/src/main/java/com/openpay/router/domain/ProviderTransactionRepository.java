package com.openpay.router.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProviderTransactionRepository extends JpaRepository<ProviderTransaction, UUID> {

    List<ProviderTransaction> findByPaymentIdOrderByAttemptNoAsc(UUID paymentId);

    boolean existsByPaymentId(UUID paymentId);
}
