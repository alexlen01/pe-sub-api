package com.ubs.pesubapi.controller;

import tools.jackson.databind.JsonNode;
import com.ubs.pesubapi.security.CurrentUserService;
import com.ubs.pesubapi.service.AuditLogService;
import com.ubs.pesubapi.service.ConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    private final ConfigService    configService;
    private final AuditLogService  auditService;
    private final CurrentUserService currentUser;

    public ConfigController(ConfigService configService, AuditLogService auditService,
                            CurrentUserService currentUser) {
        this.configService = configService;
        this.auditService  = auditService;
        this.currentUser   = currentUser;
    }

    @GetMapping("/eligibility")
    public ResponseEntity<Map<String, JsonNode>> eligibility() {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        configService.get("busa_tiers").ifPresent(v        -> out.put("BUSA_TIERS", v));
        configService.get("agent_tiers").ifPresent(v       -> out.put("AGENT_TIERS", v));
        configService.get("conc_limits").ifPresent(v       -> out.put("CONC_LIMITS", v));
        configService.get("cls_conc_limit_defaults").ifPresent(v -> out.put("CLS_CONC_LIMIT_DEFAULTS", v));
        configService.get("cls_conc_limit_bounds").ifPresent(v   -> out.put("CLS_CONC_LIMIT_BOUNDS", v));
        configService.get("bb_criteria_matrix").ifPresent(v -> out.put("BB_CRITERIA_MATRIX", v));
        configService.get("global_settings").ifPresent(v   -> out.put("GLOBAL_SETTINGS", v));
        return out.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(out);
    }

    @GetMapping("/classification")
    public ResponseEntity<JsonNode> classification() {
        return configService.get("classification_config")
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/wizard")
    public ResponseEntity<JsonNode> wizard() {
        return configService.get("wizard_config")
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/audit")
    public ResponseEntity<JsonNode> audit() {
        return configService.get("audit_config")
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/matching")
    public ResponseEntity<JsonNode> matching() {
        return configService.get("matching_config")
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    private static final Map<String, String> SECTION_LABELS = Map.of(
        "thresholds",    "Confidence Thresholds",
        "weights",       "Algorithm Weights",
        "suffixes",      "Legal Entity Suffix Rules",
        "abbreviations", "Abbreviation Expansion Dictionary"
    );

    @PutMapping("/matching")
    public ResponseEntity<JsonNode> setMatching(
            @RequestBody JsonNode body,
            @RequestParam(required = false) String section,
            HttpServletRequest req) {
        JsonNode saved = configService.put("matching_config", body).getValue();
        String label = SECTION_LABELS.getOrDefault(section, "Matching config");
        log.info("Matching config updated section='{}' label='{}'", section, label);
        auditService.log("Match Config Change", label + " updated", null, currentUser.uuName(),
            currentUser.auditDisplayName(), auditService.extractIp(req));
        return ResponseEntity.ok(saved);
    }

    private static final Map<String, String> ELIGIBILITY_LABELS = Map.of(
        "busa_tiers",        "BUSA Advance Rate Schedule",
        "agent_tiers",       "Agent Advance Rate Schedule",
        "conc_limits",       "Concentration Limits",
        "cls_conc_limit_defaults", "Per-LP Concentration Limit Defaults",
        "cls_conc_limit_bounds",   "Per-LP Concentration Limit Bounds",
        "bb_criteria_matrix",      "Borrowing Base Criteria Matrix",
        "global_settings",   "Global Settings"
    );

    /** Re-reads the config table into the in-memory cache — recovery hook for any
     *  out-of-band change to the config table. */
    @PostMapping("/reload")
    public ResponseEntity<Void> reload() {
        configService.load();
        log.info("Config cache reloaded from database");
        return ResponseEntity.noContent().build();
    }

    /**
     * Merges per-classification default concentration limits (percent of total uncalled
     * capital) into the {@code cls_conc_limit_defaults} map. Fed classes overwrite their
     * entries; unmentioned classes are preserved. Replaces the pe-sub-jobs batch job's direct
     * {@code jsonb ||} upsert against the config table — and because the merge goes through
     * ConfigService, the in-memory cache is current immediately, with no follow-up reload.
     */
    @PatchMapping("/cls-conc-limit-defaults")
    public ResponseEntity<JsonNode> mergeClsConcLimitDefaults(
            @RequestBody Map<String, BigDecimal> limits,
            HttpServletRequest req) {
        if (limits == null || limits.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        JsonNode merged = configService.mergeIntoMap("cls_conc_limit_defaults", limits);
        log.info("Cls conc limit defaults merged classes={}", limits.keySet());
        auditService.log("Config Change",
            "Per-LP Concentration Limit Defaults updated (" + limits.size() + " class"
                + (limits.size() != 1 ? "es" : "") + " fed)",
            null, currentUser.uuName(), currentUser.auditDisplayName(), auditService.extractIp(req));
        return ResponseEntity.ok(merged);
    }

    @PutMapping("/eligibility")
    public ResponseEntity<JsonNode> setEligibility(
            @RequestBody JsonNode body,
            @RequestParam String section,
            HttpServletRequest req) {
        configService.put(section, body);
        String label = ELIGIBILITY_LABELS.getOrDefault(section, section);
        log.info("Eligibility config updated section='{}' label='{}'", section, label);
        auditService.log("Config Change", label + " updated", null, currentUser.uuName(),
            currentUser.auditDisplayName(), auditService.extractIp(req));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/reports")
    public ResponseEntity<JsonNode> reports() {
        return configService.get("report_config")
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
