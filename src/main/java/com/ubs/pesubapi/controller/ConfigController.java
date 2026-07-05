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
        configService.get("agent_rate_params").ifPresent(v -> out.put("AGENT_RATE_PARAMS", v));
        configService.get("elig_rules").ifPresent(v        -> out.put("ELIG_RULES", v));
        configService.get("conc_limits").ifPresent(v       -> out.put("CONC_LIMITS", v));
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
        auditService.log("Match Config Change", label + " updated", null, currentUser.displayName(), auditService.extractIp(req));
        return ResponseEntity.ok(saved);
    }

    private static final Map<String, String> ELIGIBILITY_LABELS = Map.of(
        "busa_tiers",        "BUSA Advance Rate Schedule",
        "agent_tiers",       "Agent Advance Rate Schedule",
        "agent_rate_params", "Agent Rate Parameters",
        "elig_rules",        "Eligibility Rules",
        "conc_limits",       "Concentration Limits",
        "global_settings",   "Global Settings"
    );

    @PutMapping("/eligibility")
    public ResponseEntity<JsonNode> setEligibility(
            @RequestBody JsonNode body,
            @RequestParam String section,
            HttpServletRequest req) {
        configService.put(section, body);
        String label = ELIGIBILITY_LABELS.getOrDefault(section, section);
        log.info("Eligibility config updated section='{}' label='{}'", section, label);
        auditService.log("Config Change", label + " updated", null, currentUser.displayName(), auditService.extractIp(req));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/reports")
    public ResponseEntity<JsonNode> reports() {
        return configService.get("report_config")
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
