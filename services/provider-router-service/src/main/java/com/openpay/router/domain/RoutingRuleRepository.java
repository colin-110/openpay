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

    /**
     * One acquirer's base URL, looked up rather than filtered for.
     *
     * <p>{@code baseUrlFor} used to read every rule in the table and pick through them in Java on
     * each routing decision — correct, but a table scan per payment to answer a question about one
     * row. Disabled rules are included on purpose: disabling one means "send no new traffic here",
     * not "forget where it lives", and a callback for a payment already in flight still has to
     * reach it.
     */
    List<RoutingRule> findByProviderNameOrderByPriorityAsc(String providerName);
}
