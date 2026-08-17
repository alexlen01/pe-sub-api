package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.CommitBbRequest.CommitLpRow;
import com.ubs.pesubapi.dto.LpMasterDto;
import com.ubs.pesubapi.dto.LpMasterUpdateRequest;
import com.ubs.pesubapi.entity.LpMaster;
import com.ubs.pesubapi.entity.LpRecord;
import com.ubs.pesubapi.exception.ResourceNotFoundException;
import com.ubs.pesubapi.repository.LpMasterRepository;
import com.ubs.pesubapi.repository.LpRecordRepository;
import com.ubs.pesubapi.util.MoneyValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class LpMasterService {

    private static final Logger log = LoggerFactory.getLogger(LpMasterService.class);

    private final LpRecordRepository lpRecordRepo;
    private final LpMasterRepository lpMasterRepo;
    private final LpMasterResolutionService resolutionService;
    private final ReclassificationPolicy reclassificationPolicy;

    public LpMasterService(LpRecordRepository lpRecordRepo, LpMasterRepository lpMasterRepo,
                           LpMasterResolutionService resolutionService,
                           ReclassificationPolicy reclassificationPolicy) {
        this.lpRecordRepo = lpRecordRepo;
        this.lpMasterRepo = lpMasterRepo;
        this.resolutionService = resolutionService;
        this.reclassificationPolicy = reclassificationPolicy;
    }

    /** Outcome of an LP Master row deletion, for audit/notification messaging. */
    public record LpMasterDeletion(String investorName, int detachedRecords) {}

    /**
     * Every LP Master row with its hierarchy context resolved — the ultimate parent each row routes
     * to and how many direct children point at it. Resolved in one in-memory pass over the table
     * rather than a query per ancestor, because the LP Master Records screen lists all rows at once.
     */
    public List<LpMasterDto> listWithHierarchy() {
        List<LpMaster> all = lpMasterRepo.findAllByOrderByUbsLpCategoryAscInvestorNameAsc();
        Map<Integer, LpMaster> byId = new HashMap<>();
        Map<Integer, Integer>  childCounts = new HashMap<>();
        for (LpMaster m : all) byId.put(m.getId(), m);
        for (LpMaster m : all) {
            if (m.getParentId() != null) childCounts.merge(m.getParentId(), 1, (a, b) -> a + b);
        }
        return all.stream()
            .map(m -> {
                var resolution = resolutionService.resolveIn(m, byId);
                String ultimate = resolution.routed()
                    ? resolution.ultimateParent().getInvestorName() : null;
                return LpMasterDto.from(m, ultimate, childCounts.getOrDefault(m.getId(), 0));
            })
            .toList();
    }

    /** One LP Master row with the same hierarchy context {@link #listWithHierarchy} resolves. */
    public Optional<LpMasterDto> getWithHierarchy(int id) {
        return lpMasterRepo.findById(id).map(m -> {
            var resolution = resolutionService.resolve(m);
            String ultimate = resolution.routed() ? resolution.ultimateParent().getInvestorName() : null;
            return LpMasterDto.from(m, ultimate, lpMasterRepo.findByParentIdOrderByInvestorNameAsc(id).size());
        });
    }

    /**
     * Applies an analyst's edits to one LP Master row.
     *
     * <p>The parent link is the only field with real work behind it. {@code parentId} wins when
     * supplied; otherwise the {@code parent} name is resolved against {@code investor_name}, so a
     * sponsor typed before it exists is kept as display text and links on a later save. Both sides
     * of the pair are always written together, and {@code is_ultimate_parent} follows from the id.
     *
     * <p>Renaming a row repoints every child that named it, so the string and the link cannot drift.
     *
     * @throws ResourceNotFoundException when {@code id} does not exist
     * @throws IllegalArgumentException  when the link would be a self-reference or close a cycle
     */
    @Transactional
    public LpMasterDto update(int id, LpMasterUpdateRequest req) {
        LpMaster master = lpMasterRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("LP Master " + id + " not found"));

        String previousName = master.getInvestorName();
        String newName = req.investorName().trim();

        master.setInvestorName(newName);
        master.setSpv(req.spv());
        master.setHighQuality(req.highQuality());
        master.setInvestmentGrade(req.investmentGrade());
        master.setInvestorType(blankToNull(req.investorType()));
        master.setInstitutionalOrHnw(blankToNull(req.institutionalOrHnw()));
        master.setRegionLocation(blankToNull(req.regionLocation()));
        master.setUbsLpCategory(blankToNull(req.ubsLpCategory()));
        master.setSpRating(req.spRating());
        master.setMoodysRating(req.moodysRating());
        master.setFitchRating(req.fitchRating());
        master.setAum(blankToNull(req.aum()));
        master.setNav(blankToNull(req.nav()));
        master.setPensionAssets(blankToNull(req.pensionAssets()));
        master.setFundingRatio(req.fundingRatio());
        master.setUbsDefaultAdvanceRate(req.ubsDefaultAdvanceRate());
        master.setUbsDefaultConcentrationLimit(MoneyValues.concLimit(req.ubsDefaultConcentrationLimit()));
        master.setNotes(blankToNull(req.notes()));

        applyParentLink(master, req.parent(), req.parentId());
        lpMasterRepo.save(master);

        // Keep children's display string in step with a rename; their parent_id already points here.
        if (!previousName.equals(newName)) {
            List<LpMaster> children = lpMasterRepo.findByParentIdOrderByInvestorNameAsc(id);
            children.forEach(c -> c.setParent(newName));
            if (!children.isEmpty()) lpMasterRepo.saveAll(children);
        }
        // A row created before its sponsor existed carries the name but no link — adopt it now.
        List<LpMaster> pendingChildren = lpMasterRepo.findByParentAndParentIdIsNull(newName).stream()
            .filter(c -> !c.getId().equals(id))
            .toList();
        pendingChildren.forEach(c -> c.setParentId(id));
        if (!pendingChildren.isEmpty()) lpMasterRepo.saveAll(pendingChildren);

        log.info("LP Master updated id={} investor='{}' parentId={} adoptedChildren={}",
            id, newName, master.getParentId(), pendingChildren.size());
        return getWithHierarchy(id).orElseThrow();
    }

    /**
     * Resolve every unlinked {@code parent} string across the table in one pass.
     *
     * <p>Run after a bulk feed: rows arrive in arbitrary order, so a child ingested before its
     * sponsor has a parent name that matches nothing at the moment it is written. This closes those
     * links once the whole batch has landed. Self-references and links that would close a cycle are
     * skipped and logged rather than thrown — one bad row in a feed must not fail the ingest.
     *
     * @return number of rows newly linked
     */
    @Transactional
    public int relinkParents() {
        List<LpMaster> all = lpMasterRepo.findAll();
        Map<String, LpMaster> byName = new HashMap<>();
        Map<Integer, LpMaster> byId = new HashMap<>();
        for (LpMaster m : all) {
            byName.putIfAbsent(m.getInvestorName(), m);
            byId.put(m.getId(), m);
        }

        List<LpMaster> linked = new ArrayList<>();
        for (LpMaster m : all) {
            if (m.getParentId() != null) continue;
            String name = m.getParent() == null ? "" : m.getParent().trim();
            if (name.isEmpty()) continue;
            LpMaster parent = byName.get(name);
            if (parent == null || parent.getId().equals(m.getId())) continue;
            if (wouldCycleIn(m.getId(), parent, byId)) {
                log.warn("LP Master parent link skipped — cycle: '{}' -> '{}'",
                    m.getInvestorName(), parent.getInvestorName());
                continue;
            }
            m.setParentId(parent.getId());
            linked.add(m);
        }
        if (!linked.isEmpty()) lpMasterRepo.saveAll(linked);
        log.info("LP Master parent relink completed rows={} linked={}", all.size(), linked.size());
        return linked.size();
    }

    /**
     * Resolve and set the parent pair. A blank name with no id clears the link entirely.
     * Rejects a self-reference supplied as an explicit {@code parentId}, and any link that would
     * close a cycle — the resolution walk is cycle-tolerant, but a hierarchy that loops has no
     * ultimate entity and so no credit profile to apply, which is a data error worth surfacing
     * rather than absorbing.
     *
     * <p>A record naming <em>itself</em> in the {@code parent} string is not an error: the LP
     * Master feed uses that as its "no parent" convention, and most rows carry it. It is preserved
     * verbatim and left unlinked, so re-saving such a record does not fail.
     */
    private void applyParentLink(LpMaster master, String parentName, Integer parentId) {
        String name = parentName == null ? "" : parentName.trim();

        // The feed's own-name convention — keep the string, claim no hierarchy.
        if (parentId == null && !name.isEmpty() && name.equals(master.getInvestorName())) {
            master.setParent(name);
            master.setParentId(null);
            return;
        }

        LpMaster parent = null;
        if (parentId != null) {
            parent = lpMasterRepo.findById(parentId)
                .orElseThrow(() -> new ResourceNotFoundException("Parent LP Master " + parentId + " not found"));
        } else if (!name.isEmpty()) {
            parent = lpMasterRepo.findByInvestorName(name).orElse(null);
        }

        if (parent != null) {
            if (parent.getId().equals(master.getId())) {
                throw new IllegalArgumentException("An LP Master record cannot be its own parent");
            }
            if (wouldCycle(master.getId(), parent)) {
                throw new IllegalArgumentException(
                    "'" + parent.getInvestorName() + "' is already a descendant of '"
                        + master.getInvestorName() + "' — that link would create a cycle");
            }
            master.setParent(parent.getInvestorName());
            master.setParentId(parent.getId());
        } else {
            // Unresolved name: keep it as display text, leave the row reading as ultimate.
            master.setParent(name.isEmpty() ? null : name);
            master.setParentId(null);
        }
    }

    /** True when {@code candidateParent} already sits below {@code childId} in the hierarchy. */
    private boolean wouldCycle(Integer childId, LpMaster candidateParent) {
        return wouldCycleIn(childId, candidateParent, null);
    }

    /**
     * {@link #wouldCycle} against an already-loaded row map when one is supplied, so a batch relink
     * walks the ancestor chain in memory instead of issuing a query per level.
     */
    private boolean wouldCycleIn(Integer childId, LpMaster candidateParent, Map<Integer, LpMaster> byId) {
        Set<Integer> seen = new HashSet<>();
        LpMaster cur = candidateParent;
        while (cur != null && seen.add(cur.getId())) {
            if (childId.equals(cur.getId())) return true;
            Integer next = cur.getParentId();
            if (next == null) return false;
            cur = byId != null ? byId.get(next) : lpMasterRepo.findById(next).orElse(null);
        }
        return false;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** Outcome of a full LP Master clear, for audit/notification messaging. */
    public record LpMasterClear(long deletedMasters, int detachedRecords) {}

    /**
     * Clears the entire LP Master table ahead of a bootstrap repopulate (the one-off LP DB
     * extract feed). Facility LP records that reference master rows are detached first
     * (lp_master_id nulled) — never deleted, as they are per-facility credit data — satisfying
     * the lp_records.lp_master_id foreign key before the master rows are removed. The next
     * accepted Shadow BB write-back re-links records to the freshly seeded master by name.
     */
    @Transactional
    public LpMasterClear clearAll() {
        int detached = lpRecordRepo.clearAllLpMasterRefs();
        long deleted = lpMasterRepo.count();
        lpMasterRepo.deleteAllInBatch();
        log.info("LP Master cleared deletedMasters={} detachedFacilityRecords={}", deleted, detached);
        return new LpMasterClear(deleted, detached);
    }

    /**
     * Hard-deletes an LP Master row — the manual correction path for erroneously ingested LPs
     * that slipped past analyst/reviewer checks. Facility LP records that reference the row are
     * detached (lp_master_id nulled), never deleted: they are per-facility credit data and remain
     * authoritative for their facility. Note that if the same investor name still exists in a
     * facility's records, the next accepted Shadow BB cycle's write-back will re-create a master
     * row from that facility's data.
     */
    @Transactional
    public LpMasterDeletion delete(int id) {
        LpMaster master = lpMasterRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("LP Master " + id + " not found"));
        int detached = lpRecordRepo.clearLpMasterRef(id);
        lpMasterRepo.delete(master);
        log.info("LP Master deleted id={} investor='{}' detachedFacilityRecords={}",
            id, master.getInvestorName(), detached);
        return new LpMasterDeletion(master.getInvestorName(), detached);
    }

    /**
     * Upserts LP records for a facility from a Shadow BB run.
     * Matches by (facilityId, investorName): updates existing LPs in place (preserving their IDs
     * so FK references from match_queue_entries are not broken), inserts new LPs.
     * Incoming rows are themselves deduped by name so the same Agent BB submitted twice (or a
     * name repeated within one payload) collapses onto a single record — last value wins —
     * never violating the uq_lp_records_facility_investor constraint.
     */
    @Transactional
    public List<LpRecord> upsertAll(int facilityId, List<CommitLpRow> rows) {
        Map<String, LpRecord> byName = lpRecordRepo.findByFacilityIdOrderByInvestorNameAsc(facilityId).stream()
            .collect(Collectors.toMap(lpRecord -> lpRecord.getInvestorName(), lpRecord -> lpRecord, (a, b) -> a, LinkedHashMap::new));

        // Evaluated before this run's snapshot is written, so the wizard's first Run Shadow BB
        // cannot carry a client-side reclassified flag onto brand-new records; re-runs can.
        boolean marksReclassification = reclassificationPolicy.marksReclassification(facilityId);

        Map<String, LpRecord> toSave = new LinkedHashMap<>();
        int seq = 0;
        for (CommitLpRow row : rows) {
            String name = row.investorName() != null ? row.investorName() : "";
            // DEBUG carries the full record payload so a per-record persistence failure
            // (e.g. "value too long for character varying(N)") is attributable to a specific
            // LP in lower environments; INFO+ (higher environments) logs nothing per record.
            log.debug("Upserting LP record: facilityId={} seq={} name='{}' row={}",
                facilityId, seq, name, row);
            LpRecord lpRecord = byName.computeIfAbsent(name, n -> new LpRecord());
            apply(lpRecord, facilityId, row, marksReclassification);
            lpRecord.setSourceSeq(seq++);   // preserve the submitted (source-file) order
            toSave.put(name, lpRecord);
        }
        log.debug("Upsert complete: facilityId={} submittedRows={} distinctRecords={}",
            facilityId, rows.size(), toSave.size());
        return lpRecordRepo.saveAll(new ArrayList<>(toSave.values()));
    }

    private void apply(LpRecord lpRecord, int facilityId, CommitLpRow row, boolean marksReclassification) {
        lpRecord.setFacilityId(facilityId);
        lpRecord.setInvestorName(row.investorName() != null ? row.investorName() : "");
        lpRecord.setParent(row.parent());
        lpRecord.setSpv(row.spv());
        lpRecord.setHighQuality(row.highQuality());
        lpRecord.setInvestorType(row.investorType() != null ? row.investorType() : "");
        lpRecord.setInstitutionalOrHnw(row.institutionalOrHnw() != null ? row.institutionalOrHnw() : "Institutional");
        lpRecord.setRegionLocation(row.regionLocation() != null ? row.regionLocation() : "");
        lpRecord.setInvestmentGrade(row.investmentGrade());
        lpRecord.setUbsLpCategory(row.ubsLpCategory() != null ? row.ubsLpCategory() : "Eligible");
        lpRecord.setAgentLpCategory(row.agentLpCategory());
        lpRecord.setAgentLpCategorySource(normalizeAgentClsSource(row.agentLpCategorySource()));
        lpRecord.setSpRating(row.spRating()    != null ? row.spRating()    : "");
        lpRecord.setMoodysRating(row.moodysRating()  != null ? row.moodysRating()   : "");
        lpRecord.setFitchRating(row.fitchRating() != null ? row.fitchRating() : "");
        lpRecord.setAum(row.aum());
        lpRecord.setNav(row.nav());
        lpRecord.setPensionAssets(row.pensionAssets());
        lpRecord.setFundingRatio(MoneyValues.ratio(row.fundingRatio()));
        lpRecord.setCapitalCommitment(MoneyValues.dollars(row.capitalCommitment()));
        lpRecord.setPctOfFundCommitments(MoneyValues.fraction(row.pctOfFundCommitments()));
        lpRecord.setCalledCapital(MoneyValues.dollars(row.calledCapital()));
        lpRecord.setUncalledCapital(MoneyValues.dollars(row.uncalledCapital()));
        lpRecord.setPctOfFundUncalled(MoneyValues.fraction(row.pctOfFundUncalled()));
        lpRecord.setPctLpCalled(MoneyValues.fraction(row.pctLpCalled()));
        lpRecord.setAgentConcentrationLimit(MoneyValues.decimal(row.agentConcentrationLimit()));
        lpRecord.setUbsConcentrationLimit(MoneyValues.concLimit(row.ubsConcentrationLimit()));
        // Persist the resolved UBS advance rate alongside the conc limit so the accepted run's
        // credit profile round-trips to LP Master (writeBackToLpMaster reads ubsRate). Guarded so an
        // older client that omits the field does not clear a previously stored per-LP rate.
        if (row.ubsAdvanceRate() != null) {
            lpRecord.setUbsAdvanceRate(MoneyValues.fraction(row.ubsAdvanceRate()));
        }
        lpRecord.setAgentAdvanceRate(MoneyValues.fraction(row.agentAdvanceRate()));
        // Engine outputs (abb, ubb, excess concentrations, rank) are not taken from the
        // payload: abb stays whatever ingest wrote (agent-reported), and the rest are computed
        // and written back by ShadowBbService in the same run transaction.
        lpRecord.setIncluded(row.included());
        // Reclassification is a sticky record status. A Shadow BB client can legitimately carry
        // a stale false value from a snapshot loaded before the classification Save; never let a
        // later run erase the server-authoritative flag. Before the submission's first run the
        // client's flag is ignored outright — nothing has been run that a change could invalidate.
        lpRecord.setReclassified(lpRecord.isReclassified() || (marksReclassification && row.reclassified()));
        lpRecord.setTransferee(row.transferee());
        lpRecord.setNotes(row.notes());
    }

    private static String normalizeAgentClsSource(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().toUpperCase();
        return switch (value) {
            case "EXTRACTED", "DERIVED", "USER_EDITED" -> value;
            default -> null;
        };
    }
}
