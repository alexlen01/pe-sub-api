package com.ubs.pesubapi.service;

import com.ubs.pesubapi.entity.Submission;
import com.ubs.pesubapi.exception.ResourceNotFoundException;
import com.ubs.pesubapi.repository.AuditLogRepository;
import com.ubs.pesubapi.repository.BbSnapshotRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.LpRepository;
import com.ubs.pesubapi.repository.MatchQueueEntryRepository;
import com.ubs.pesubapi.repository.SubmissionExtractionRepository;
import com.ubs.pesubapi.repository.SubmissionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FacilityService {

    private final FacilityRepository facilityRepo;
    private final LpRepository lpRepo;
    private final SubmissionRepository submissionRepo;
    private final SubmissionExtractionRepository extractionRepo;
    private final MatchQueueEntryRepository matchQueueRepo;
    private final BbSnapshotRepository snapshotRepo;
    private final AuditLogRepository auditLogRepo;

    public FacilityService(FacilityRepository facilityRepo,
                           LpRepository lpRepo,
                           SubmissionRepository submissionRepo,
                           SubmissionExtractionRepository extractionRepo,
                           MatchQueueEntryRepository matchQueueRepo,
                           BbSnapshotRepository snapshotRepo,
                           AuditLogRepository auditLogRepo) {
        this.facilityRepo   = facilityRepo;
        this.lpRepo         = lpRepo;
        this.submissionRepo = submissionRepo;
        this.extractionRepo = extractionRepo;
        this.matchQueueRepo = matchQueueRepo;
        this.snapshotRepo   = snapshotRepo;
        this.auditLogRepo   = auditLogRepo;
    }

    /**
     * Hard-delete a facility along with its non-LP dependents (submissions and their extractions,
     * match-queue entries, Shadow BB snapshots). Permitted only when the facility carries no LP
     * records — committed LP data must never be silently destroyed. audit_log rows are preserved
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

        long lpCount = lpRepo.countByFacilityId(id);
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
