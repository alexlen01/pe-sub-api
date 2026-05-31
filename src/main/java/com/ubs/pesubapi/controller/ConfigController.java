package com.ubs.pesubapi.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ubs.pesubapi.service.ConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
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

    @GetMapping("/reports")
    public ResponseEntity<JsonNode> reports() {
        return configService.get("report_config")
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
