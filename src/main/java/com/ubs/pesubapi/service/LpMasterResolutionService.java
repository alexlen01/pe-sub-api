package com.ubs.pesubapi.service;

import com.ubs.pesubapi.entity.LpMaster;
import com.ubs.pesubapi.repository.LpMasterRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Parent/child routing for a matched LP Master record — Phase 3 of
 * {@code pe-sub-docs/LP_Mapping_and_Database_Architecture.md}.
 *
 * <p>Borrowing base eligibility and concentration limits turn on the creditworthiness of the
 * ultimate entity, which for a feeder fund or SPV sits at the parent/sponsor level. Once the
 * matching engine picks a record, this service walks {@code parent_id} to the ultimate entity and
 * exposes the chain so callers can resolve each field <em>child-first, ancestors filling gaps</em>:
 * a value the matched record carries wins, and only where it is absent does the parent supply one.
 *
 * <p>The matched (child) record stays the identity of record — {@code lp_records.lp_master_id}
 * points at it, not at the parent — so the audit trail keeps naming the entity the agent listed.
 */
@Service
public class LpMasterResolutionService {

    /**
     * Defensive bound on the ancestor walk. Real sponsor hierarchies are two or three deep;
     * this only ever binds on data corrupted past the cycle guard below.
     */
    private static final int MAX_DEPTH = 16;

    private final LpMasterRepository lpMasterRepo;

    public LpMasterResolutionService(LpMasterRepository lpMasterRepo) {
        this.lpMasterRepo = lpMasterRepo;
    }

    /**
     * A matched record together with its ancestor chain.
     *
     * @param matched        the record the matching engine selected — never null
     * @param chain          {@code matched} first, then each ancestor up to the ultimate entity
     * @param ultimateParent the last element of {@code chain} when the match is a child/feeder;
     *                       {@code null} when {@code matched} is itself the ultimate entity
     */
    public record Resolution(LpMaster matched, List<LpMaster> chain, LpMaster ultimateParent) {

        /** True when the match was a child/feeder and details route up to a parent. */
        public boolean routed() { return ultimateParent != null; }

        /** Display name of the entity whose profile is applied — the parent when routed. */
        public String appliedName() {
            return routed() ? ultimateParent.getInvestorName() : matched.getInvestorName();
        }

        /**
         * First non-null value for {@code getter} walking child → ultimate parent. This is the
         * "child wins, parent fills the gap" rule; a field the child carries is never overwritten.
         */
        public <T> Optional<T> value(Function<LpMaster, T> getter) {
            for (LpMaster m : chain) {
                T v = getter.apply(m);
                if (v != null) return Optional.of(v);
            }
            return Optional.empty();
        }

        /** {@link #value} for strings, treating blank as absent — ratings default to {@code ""}. */
        public Optional<String> text(Function<LpMaster, String> getter) {
            for (LpMaster m : chain) {
                String v = getter.apply(m);
                if (v != null && !v.isBlank()) return Optional.of(v);
            }
            return Optional.empty();
        }

        /**
         * True when the matched record or any ancestor asserts the flag. Booleans carry no "absent"
         * state, so the gap-fill rule cannot apply; this is for the flags a sponsor confers on its
         * feeders — investment grade rides on the parent's rating. Flags that describe the entity
         * itself rather than its credit standing (SPV status) must read {@link #matched} directly.
         */
        public boolean flag(Function<LpMaster, Boolean> getter) {
            for (LpMaster m : chain) {
                if (Boolean.TRUE.equals(getter.apply(m))) return true;
            }
            return false;
        }
    }

    /** Resolve one record by name. Empty when no LP Master row carries that name. */
    public Optional<Resolution> resolveByName(String investorName) {
        if (investorName == null || investorName.isBlank()) return Optional.empty();
        return lpMasterRepo.findByInvestorName(investorName.trim()).map(this::resolve);
    }

    /** Resolve one record by id. Empty when the id does not exist. */
    public Optional<Resolution> resolveById(Integer id) {
        if (id == null) return Optional.empty();
        return lpMasterRepo.findById(id).map(this::resolve);
    }

    /**
     * Walk {@code matched} to its ultimate parent. Self-references and cycles terminate the walk at
     * the last record visited rather than throwing — a corrupted link must not fail an upload, and
     * the partial chain still resolves every field the walk reached.
     */
    public Resolution resolve(LpMaster matched) {
        List<LpMaster> chain = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        LpMaster cur = matched;
        while (cur != null && chain.size() < MAX_DEPTH && seen.add(cur.getId())) {
            chain.add(cur);
            Integer parentId = cur.getParentId();
            cur = (parentId == null || seen.contains(parentId))
                ? null
                : lpMasterRepo.findById(parentId).orElse(null);
        }
        LpMaster ultimate = chain.size() > 1 ? chain.getLast() : null;
        return new Resolution(matched, List.copyOf(chain), ultimate);
    }

    /**
     * Resolve many records in one pass, for the match queue and the LP Master list screen. Loads
     * every row once and walks the chains in memory rather than issuing a query per ancestor.
     *
     * @return resolutions keyed by matched investor name, preserving {@code investorNames} order
     */
    public Map<String, Resolution> resolveAllByName(Collection<String> investorNames) {
        Map<String, Resolution> out = new LinkedHashMap<>();
        if (investorNames == null || investorNames.isEmpty()) return out;

        Map<Integer, LpMaster> byId = new HashMap<>();
        for (LpMaster m : lpMasterRepo.findAll()) byId.put(m.getId(), m);
        Map<String, LpMaster> byName = new HashMap<>();
        for (LpMaster m : byId.values()) byName.putIfAbsent(m.getInvestorName(), m);

        for (String name : investorNames) {
            if (name == null || name.isBlank()) continue;
            LpMaster matched = byName.get(name.trim());
            if (matched != null) out.put(name, resolveIn(matched, byId));
        }
        return out;
    }

    /** {@link #resolve} against an already-loaded row map — no per-ancestor query. */
    public Resolution resolveIn(LpMaster matched, Map<Integer, LpMaster> byId) {
        List<LpMaster> chain = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        LpMaster cur = matched;
        while (cur != null && chain.size() < MAX_DEPTH && seen.add(cur.getId())) {
            chain.add(cur);
            Integer parentId = cur.getParentId();
            cur = (parentId == null || seen.contains(parentId)) ? null : byId.get(parentId);
        }
        LpMaster ultimate = chain.size() > 1 ? chain.getLast() : null;
        return new Resolution(matched, List.copyOf(chain), ultimate);
    }
}
