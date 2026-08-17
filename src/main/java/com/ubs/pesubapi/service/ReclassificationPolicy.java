package com.ubs.pesubapi.service;

import com.ubs.pesubapi.entity.BbSnapshot;
import com.ubs.pesubapi.entity.Submission;
import com.ubs.pesubapi.repository.BbSnapshotRepository;
import com.ubs.pesubapi.repository.SubmissionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a classification change on a facility LP record counts as a <em>reclassification</em>.
 *
 * <p>The flag means "the facility's Shadow BB is stale because an LP's Agent/UBS LP Category moved
 * after the run" — it drives the R badge, the "Re-run Shadow BB and submit for Manager approval"
 * banner and the reclassified reports. While the analyst is still working through the Upload Agent
 * BB wizard (steps 1–5) there is no run to invalidate yet: every category the analyst sets is
 * initial data entry, not a reclassification. Marking those edits produced R badges and re-run
 * warnings before the first Shadow BB even existed.
 *
 * <p>Marking is therefore active only once the <em>current</em> submission has produced a Shadow BB
 * snapshot. A snapshot left over from an earlier, already-completed submission does not count: a
 * fresh wizard run starts the cycle again. With no open submission (LP Master edits outside any
 * wizard) any existing snapshot is the live one, so marking is active.
 */
@Service
public class ReclassificationPolicy {

    /** Submission statuses that mean "the wizard is still open on this submission". */
    private static final Set<String> OPEN_STATUSES = Set.of("Processing", "Review");

    private final BbSnapshotRepository snapshots;
    private final SubmissionRepository submissions;

    public ReclassificationPolicy(BbSnapshotRepository snapshots, SubmissionRepository submissions) {
        this.snapshots   = snapshots;
        this.submissions = submissions;
    }

    /**
     * @return true when a classification change on this facility should set the reclassified flag,
     *         i.e. a Shadow BB has already been created for the submission currently in flight.
     */
    public boolean marksReclassification(Integer facilityId) {
        if (facilityId == null) return false;
        Optional<BbSnapshot> latest = snapshots.findTopByFacilityIdOrderByCalculatedAtDesc(facilityId);
        if (latest.isEmpty()) return false;                 // facility has never been run

        LocalDateTime runAt = latest.get().getCalculatedAt();
        return openSubmission(facilityId)
            .map(open -> !runAt.isBefore(open.getCreatedAt()))
            .orElse(true);
    }

    /** The newest submission still being worked through the wizard, if any. */
    private Optional<Submission> openSubmission(Integer facilityId) {
        return submissions.findFirstByFacilityIdAndStatusInOrderByCreatedAtDesc(facilityId, OPEN_STATUSES);
    }
}
