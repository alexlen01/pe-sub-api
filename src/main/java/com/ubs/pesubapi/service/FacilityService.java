package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.FacilityIngestRow;
import com.ubs.pesubapi.dto.IngestSummary;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.Submission;
import com.ubs.pesubapi.exception.ResourceNotFoundException;
import com.ubs.pesubapi.repository.AuditLogRepository;
import com.ubs.pesubapi.repository.BbSnapshotRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;
import com.ubs.pesubapi.repository.MatchQueueEntryRepository;
import com.ubs.pesubapi.repository.SubmissionExtractionRepository;
import com.ubs.pesubapi.repository.SubmissionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class FacilityService {

    private final FacilityRepository facilityRepo;
    private final LpRecordRepository lpRecordRepo;
    private final SubmissionRepository submissionRepo;
    private final SubmissionExtractionRepository extractionRepo;
    private final MatchQueueEntryRepository matchQueueRepo;
    private final BbSnapshotRepository snapshotRepo;
    private final AuditLogRepository auditLogRepo;

    public FacilityService(FacilityRepository facilityRepo,
                           LpRecordRepository lpRecordRepo,
                           SubmissionRepository submissionRepo,
                           SubmissionExtractionRepository extractionRepo,
                           MatchQueueEntryRepository matchQueueRepo,
                           BbSnapshotRepository snapshotRepo,
                           AuditLogRepository auditLogRepo) {
        this.facilityRepo   = facilityRepo;
        this.lpRecordRepo         = lpRecordRepo;
        this.submissionRepo = submissionRepo;
        this.extractionRepo = extractionRepo;
        this.matchQueueRepo = matchQueueRepo;
        this.snapshotRepo   = snapshotRepo;
        this.auditLogRepo   = auditLogRepo;
    }

    /**
     * Bulk upsert from the pe-sub-jobs facility feed, keyed by facility name.
     * New facilities take their platform status from the agent-reported {@code bankStatus}
     * (Active/Inactive; anything else -> 'Not Started') so a freshly-seeded book reflects each
     * facility's real-world standing in LP Master; the concentration limit defaults to 25M.
     * Existing facilities have their agent-reported fields overwritten in place — including with
     * nulls, matching the feed's replace semantics — while platform-owned fields (status,
     * concLimitM, facilitySize, lastRunAt) are never touched, so re-feeding never disturbs the
     * Shadow BB workflow state of an already-onboarded facility. Rows without a name or agent
     * bank are skipped.
     */
    @Transactional
    public IngestSummary ingest(List<FacilityIngestRow> rows) {
        int created = 0, updated = 0, skipped = 0;
        for (FacilityIngestRow row : rows) {
            if (row == null
                    || row.name() == null || row.name().isBlank()
                    || row.agentBank() == null || row.agentBank().isBlank()) {
                skipped++;
                continue;
            }
            String name = row.name().trim();
            // orElseGet keeps the variable provably non-null for static analysis; a new
            // (unsaved) entity is recognised by its not-yet-generated id.
            Facility facility = facilityRepo.findByName(name).orElseGet(() -> {
                Facility fresh = new Facility();
                fresh.setName(name);
                return fresh;
            });
            boolean isNew = facility.getId() == null;
            facility.setAgentBank(row.agentBank().trim());
            facility.setAccountNumber(row.accountNumber());
            facility.setLoanAmount(row.loanAmount());
            facility.setMaturityDate(row.maturityDate());
            facility.setBankStatus(row.bankStatus());
            facility.setBankStatusDate(row.bankStatusDate());
            facility.setUbsParticipation(row.ubsParticipation());
            facility.setCollateralDate(row.collateralDate());
            // Seed the platform status from the agent's bank_status on CREATE only; never on
            // update, where platform-owned status is deliberately preserved.
            if (isNew) {
                facility.setStatus(seedStatusFromBankStatus(row.bankStatus()));
            }
            facilityRepo.save(facility);
            if (isNew) created++; else updated++;
        }
        return new IngestSummary(created, updated, skipped);
    }

    /**
     * Map an agent-reported {@code bank_status} to the platform facility status used when SEEDING a
     * new facility from the feed. Only the two standing values the feed emits are recognised
     * ("Active"/"Inactive", case-insensitive); anything else — including null/blank — falls back to
     * the schema default "Not Started".
     */
    private static String seedStatusFromBankStatus(String bankStatus) {
        if (bankStatus == null) return "Not Started";
        return switch (bankStatus.trim().toLowerCase()) {
            case "active"   -> "Active";
            case "inactive" -> "Inactive";
            default         -> "Not Started";
        };
    }

    /**
     * Hard-delete a facility along with its non-LpRecord dependents (submissions and their extractions,
     * match-queue entries, Shadow BB snapshots). Permitted only when the facility carries no LpRecord
     * records — committed LP Data must never be silently destroyed. audit_log rows are preserved
     * (their facility_id is nulled) so the history survives the deletion.
     *
     * @throws ResourceNotFoundException if no facility with the given id exists (→ 404)
     * @throws ResponseStatusException   409 if the facility still has LP records
     */
    @Transactional
    public void delete(int id) {
        if (!facilityRepo.existsById(id)) {
            throw new ResourceNotFoundException("Facility " + id + " not found");
        }

        long lpCount = lpRecordRepo.countByFacilityId(id);
        if (lpCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cannot delete a facility that has LP records (" + lpCount + "). Remove its LP records first.");
        }

        // Delete in FK-dependency order: extractions and match-queue entries reference submissions,
        // so they go before submissions; snapshots reference the facility directly.
        for (Submission s : submissionRepo.findByFacilityIdOrderByCreatedAtDesc(id)) {
            extractionRepo.deleteBySubmissionId(s.getId());
        }
        matchQueueRepo.deleteByFacilityId(id);
        submissionRepo.deleteByFacilityId(id);
        snapshotRepo.deleteByFacilityId(id);
        auditLogRepo.clearFacilityRef(id);
        facilityRepo.deleteById(id);
    }
}
