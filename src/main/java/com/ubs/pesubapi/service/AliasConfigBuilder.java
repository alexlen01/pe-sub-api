package com.ubs.pesubapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubs.pesubapi.entity.FmAlias;
import com.ubs.pesubapi.entity.FmCanonicalField;
import com.ubs.pesubapi.repository.FmAliasRepository;
import com.ubs.pesubapi.repository.FmCanonicalFieldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the alias configuration that pe-sub-api passes to pe-sub-extraction on every
 * extract call.  The result is a JSON string keyed by extraction_key (when set) or
 * canonical name so that pe-sub-extraction's HeaderMatcher uses live, user-configurable
 * DB aliases instead of its hardcoded fallback map.
 * Bank-scoped aliases are included only when they match the current submission's agent bank;
 * global aliases are always included.
 *
 * Matching follows two tiers:
 *   1. Canonical name match  — the canonical field name is always the first entry in
 *      every field's alias list, guaranteeing a confidence-1.0 match when a template
 *      column header exactly equals the canonical name, regardless of what fm_aliases
 *      contains.
 *   2. Field Mapping Dictionary — the fm_aliases rows for that field follow, providing
 *      coverage for the alias variations registered by administrators.
 */
@Service
public class AliasConfigBuilder {

    private static final Logger log = LoggerFactory.getLogger(AliasConfigBuilder.class);

    private final FmCanonicalFieldRepository canonicalRepo;
    private final FmAliasRepository          aliasRepo;
    private final ObjectMapper               mapper;

    public AliasConfigBuilder(FmCanonicalFieldRepository canonicalRepo,
                              FmAliasRepository aliasRepo,
                              ObjectMapper mapper) {
        this.canonicalRepo = canonicalRepo;
        this.aliasRepo     = aliasRepo;
        this.mapper        = mapper;
    }

    /**
     * Returns a JSON string of shape {@code { "INVESTOR_NAME": ["alias1", ...], ... }}
     * or null if the DB is empty / serialisation fails (caller falls back to hardcoded).
     */
    public String buildJson() {
        return buildJson(null);
    }

    public String buildJson(String agentBank) {
        List<FmCanonicalField> allFields =
            canonicalRepo.findAllByOrderByGroupSortAscFieldSortAsc();
        if (allFields.isEmpty()) return null;

        // Extractable fields use extraction_key as the map key; others use canonical value
        Map<Integer, String> keyById = allFields.stream()
            .collect(Collectors.toMap(
                field -> field.getId(),
                f -> f.getExtractionKey() != null ? f.getExtractionKey() : f.getCanonical()
            ));

        List<FmAlias> allAliases = aliasRepo.findAllByOrderByCanonicalFieldIdAscAliasSortAsc();

        Map<String, List<String>> result = new LinkedHashMap<>();

        // Tier 2: Field Mapping Dictionary — fm_aliases rows in admin-configured order.
        for (FmAlias alias : allAliases) {
            if (!aliasAppliesToAgent(alias, agentBank)) continue;
            String key = keyById.get(alias.getCanonicalFieldId());
            if (key == null) continue;
            result.computeIfAbsent(key, k -> new ArrayList<>()).add(alias.getAliasText());
        }

        // Tier 1: Canonical name match — the canonical field name must be at index 0 in
        // every field's alias list so HeaderMatcher's Phase 1 (exact check against the
        // first entry) always finds it.  If the canonical name is already somewhere in
        // the list it is moved to the front; if it is absent it is prepended.
        for (FmCanonicalField cf : allFields) {
            String key   = keyById.get(cf.getId());
            List<String> aliases = result.computeIfAbsent(key, k -> new ArrayList<>());
            int idx = aliases.indexOf(cf.getCanonical());
            if (idx < 0) {
                aliases.add(0, cf.getCanonical());
            } else if (idx > 0) {
                aliases.remove(idx);
                aliases.add(0, cf.getCanonical());
            }
        }

        try {
            return mapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise alias config — extraction will use hardcoded fallback: {}", e.getMessage());
            return null;
        }
    }

    private boolean aliasAppliesToAgent(FmAlias alias, String agentBank) {
        if (alias.getBank() == null || alias.getBank().isBlank()) return true;
        if (agentBank == null || agentBank.isBlank()) return false;
        String aliasBank = normalize(alias.getBank());
        String agent = normalize(agentBank);
        if (aliasBank.equals(agent) || agent.contains(aliasBank) || aliasBank.contains(agent)) return true;
        return acronym(agent).startsWith(aliasBank);
    }

    private String normalize(String value) {
        return value.toLowerCase()
            .replaceAll("[^a-z0-9\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private String acronym(String value) {
        StringBuilder result = new StringBuilder();
        for (String token : value.split("\\s+")) {
            if (!token.isBlank()) result.append(token.charAt(0));
        }
        return result.toString();
    }
}
