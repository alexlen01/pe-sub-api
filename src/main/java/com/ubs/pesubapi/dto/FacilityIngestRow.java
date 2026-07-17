package com.ubs.pesubapi.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One facility row from the pe-sub-jobs facility feed (Agent Bank Summary CSV).
 * Upserted by {@code name}: existing facilities are updated in place; new ones are created with a
 * platform status seeded from {@code bankStatus} (Active/Inactive, else 'Not Started') and a 25M
 * concentration limit. The platform-owned {@code status} (after creation), {@code concLimitM},
 * {@code facilitySize} and {@code lastRunAt} are deliberately not part of the feed — they are
 * owned by the platform, not the ingest.
 */
public record FacilityIngestRow(
        String name,
        String agentBank,
        String accountNumber,
        BigDecimal loanAmount,
        LocalDate maturityDate,
        String bankStatus,
        LocalDate bankStatusDate,
        BigDecimal ubsParticipation,
        LocalDate collateralDate) {}
