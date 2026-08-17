package com.ubs.pesubapi.service;

import com.ubs.pesubapi.entity.LpAlias;
import com.ubs.pesubapi.repository.LpAliasRepository;
import com.ubs.pesubapi.repository.LpMasterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The matching feedback loop — Phase 5 of
 * {@code pe-sub-docs/LP_Mapping_and_Database_Architecture.md}.
 *
 * <p>When an analyst accepts a match, the uploaded Agent BB string is recorded against the LP Master
 * record it resolved to. The next upload carrying that string short-circuits fuzzy scoring at score
 * 100, while the parent/child routing still runs exactly as it does for a fuzzy hit.
 *
 * <p>Alias keys are canonicalised — trimmed, internal whitespace collapsed, upper-cased — so the
 * table's unique index means one string, one owner, and a lookup is a genuine exact match rather
 * than a case-sensitive near miss. The agent's original spelling is not lost: it stays on
 * {@code match_queue_entries.extracted_name} for the audit trail.
 */
@Service
public class LpAliasService {

    private static final Logger log = LoggerFactory.getLogger(LpAliasService.class);

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final LpAliasRepository  aliasRepo;
    private final LpMasterRepository lpMasterRepo;

    public LpAliasService(LpAliasRepository aliasRepo, LpMasterRepository lpMasterRepo) {
        this.aliasRepo    = aliasRepo;
        this.lpMasterRepo = lpMasterRepo;
    }

    /** Canonical alias key: trimmed, whitespace-collapsed, upper-cased. Blank input yields "". */
    public static String key(String uploadedName) {
        if (uploadedName == null) return "";
        return WHITESPACE.matcher(uploadedName.trim()).replaceAll(" ").toUpperCase(Locale.ROOT);
    }

    /** LP Master id an uploaded string has previously been accepted against, if any. */
    public Optional<Integer> lookup(String uploadedName) {
        String k = key(uploadedName);
        if (k.isEmpty()) return Optional.empty();
        return aliasRepo.findByUploadedName(k).map(LpAlias::getLpMasterId);
    }

    /**
     * Bulk form of {@link #lookup} for a whole upload — one query instead of one per row.
     *
     * @return canonical alias key → LP Master id, for the subset of names that have an alias
     */
    public Map<String, Integer> lookupAll(Collection<String> uploadedNames) {
        Map<String, Integer> out = new HashMap<>();
        if (uploadedNames == null || uploadedNames.isEmpty()) return out;
        List<String> keys = uploadedNames.stream().map(LpAliasService::key).filter(k -> !k.isEmpty()).distinct().toList();
        if (keys.isEmpty()) return out;
        for (LpAlias alias : aliasRepo.findByUploadedNameIn(keys)) {
            out.put(alias.getUploadedName(), alias.getLpMasterId());
        }
        return out;
    }

    /**
     * Record an accepted match. No-op when the string already resolves to the same record; an alias
     * pointing elsewhere is repointed, because the analyst's latest decision is the current truth.
     * Aliases are stored against the record that was matched — the child/feeder — never its parent.
     *
     * @return true when a row was written or repointed
     */
    @Transactional
    public boolean remember(String uploadedName, Integer lpMasterId) {
        String k = key(uploadedName);
        if (k.isEmpty() || lpMasterId == null) return false;
        if (!lpMasterRepo.existsById(lpMasterId)) return false;

        Optional<LpAlias> existing = aliasRepo.findByUploadedName(k);
        if (existing.isPresent()) {
            LpAlias alias = existing.get();
            if (lpMasterId.equals(alias.getLpMasterId())) return false;
            alias.setLpMasterId(lpMasterId);
            aliasRepo.save(alias);
            log.info("LP alias repointed uploadedName='{}' lpMasterId={}", k, lpMasterId);
            return true;
        }
        aliasRepo.save(new LpAlias(lpMasterId, k));
        log.info("LP alias learned uploadedName='{}' lpMasterId={}", k, lpMasterId);
        return true;
    }

    /** Aliases recorded against one LP Master record, for the LP Master detail panel. */
    public List<String> aliasesFor(Integer lpMasterId) {
        if (lpMasterId == null) return List.of();
        return aliasRepo.findByLpMasterIdOrderByUploadedNameAsc(lpMasterId).stream()
            .map(LpAlias::getUploadedName)
            .toList();
    }
}
