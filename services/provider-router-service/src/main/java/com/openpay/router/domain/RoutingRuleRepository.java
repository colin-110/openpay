package com.openpay.router.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutingRuleRepository extends JpaRepository<RoutingRule, UUID> {

    /** Rules that apply to everyone, in the order they are tried. */
    List<RoutingRule> findByEnabledTrueAndMerchantIdIsNullOrderByPriorityAsc();

    /** A merchant's own rules, which replace the general ones when there are any. */
    List<RoutingRule> findByEnabledTrueAndMerchantIdOrderByPriorityAsc(UUID merchantId);

    List<RoutingRule> findAllByOrderByPriorityAsc();
}
