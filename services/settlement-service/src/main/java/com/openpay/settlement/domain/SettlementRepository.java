package com.openpay.settlement.domain;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SettlementRepository extends JpaRepository<Settlement, UUID> {

    Optional<Settlement> findByMerchantIdAndCurrencyAndSettlementDate(
            UUID merchantId, String currency, LocalDate settlementDate);

    Page<Settlement> findByMerchantIdOrderBySettlementDateDesc(UUID merchantId, Pageable pageable);

    Page<Settlement> findAllByOrderBySettlementDateDesc(Pageable pageable);
}
