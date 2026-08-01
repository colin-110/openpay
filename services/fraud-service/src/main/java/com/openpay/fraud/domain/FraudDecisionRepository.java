package com.openpay.fraud.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FraudDecisionRepository extends JpaRepository<FraudDecision, UUID> {

    Optional<FraudDecision> findByPaymentId(UUID paymentId);

    /** Backs {@link RuleType#VELOCITY_COUNT}. */
    long countByMerchantIdAndCreatedAtAfter(UUID merchantId, OffsetDateTime since);

    /** Backs {@link RuleType#REPEATED_AMOUNT}. */
    long countByMerchantIdAndAmountAndCreatedAtAfter(UUID merchantId, Long amount, OffsetDateTime since);

    /**
     * The review queue, oldest first.
     *
     * <p>Oldest first because a review queue worked newest-first starves its tail, and the payment
     * at the tail is a merchant's customer waiting at a checkout.
     */
    @Query("""
            SELECT d FROM FraudDecision d
            WHERE d.outcome = com.openpay.fraud.domain.DecisionOutcome.REVIEW
              AND d.resolvedOutcome IS NULL
              AND (:merchantId IS NULL OR d.merchantId = :merchantId)
            ORDER BY d.createdAt ASC
            """)
    List<FraudDecision> findOpenReviews(@Param("merchantId") UUID merchantId, Pageable pageable);

    long countByOutcomeAndResolvedOutcomeIsNull(DecisionOutcome outcome);
}
