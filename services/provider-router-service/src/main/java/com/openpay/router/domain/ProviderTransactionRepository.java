package com.openpay.router.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProviderTransactionRepository extends JpaRepository<ProviderTransaction, UUID> {

    List<ProviderTransaction> findByPaymentIdOrderByAttemptNoAsc(UUID paymentId);

    boolean existsByPaymentId(UUID paymentId);

    /**
     * The attempt that the provider actually accepted.
     *
     * <p>A refund has to go back through the acquirer that took the money; sending it to a
     * different provider would be asking a bank to return funds it never received.
     */
    Optional<ProviderTransaction> findFirstByPaymentIdAndStatusOrderByAttemptNoDesc(
            UUID paymentId, String status);
}
