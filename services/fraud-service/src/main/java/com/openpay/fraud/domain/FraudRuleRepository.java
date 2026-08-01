package com.openpay.fraud.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudRuleRepository extends JpaRepository<FraudRule, UUID> {

    /** Evaluation order. Lowest priority number first; the first match wins. */
    List<FraudRule> findByEnabledTrueOrderByPriorityAsc();

    List<FraudRule> findAllByOrderByPriorityAsc();

    Optional<FraudRule> findByName(String name);
}
