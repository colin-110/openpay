package com.openpay.settlement.application;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes the settlement window on a schedule.
 *
 * <p>Switchable so tests and demos can drive the run explicitly instead of waiting for a clock.
 */
@Component
@ConditionalOnProperty(name = "openpay.settlement.scheduled", havingValue = "true", matchIfMissing = true)
public class SettlementRunJob {

    private static final Logger log = LoggerFactory.getLogger(SettlementRunJob.class);

    private final SettlementService settlementService;

    public SettlementRunJob(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @Scheduled(cron = "${openpay.settlement.cron:0 0 2 * * *}", zone = "UTC")
    public void run() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        log.info("Starting scheduled settlement run for {}", today);
        settlementService.runSettlement(today);
    }
}
