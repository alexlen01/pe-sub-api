package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.entity.FmAlias;
import com.ubs.pesubapi.entity.FmBlocklistEntry;
import com.ubs.pesubapi.entity.FmCanonicalField;
import com.ubs.pesubapi.entity.FmSuggestion;
import com.ubs.pesubapi.repository.FmAliasRepository;
import com.ubs.pesubapi.repository.FmBlocklistRepository;
import com.ubs.pesubapi.repository.FmCanonicalFieldRepository;
import com.ubs.pesubapi.repository.FmSuggestionRepository;
import com.ubs.pesubapi.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/field-mapping")
public class FieldMappingController {

    record AliasEntryDto(Integer id, String text, String tier, String bank) {}
    record FieldDto(Integer id, String canonical, String lpMasterField, String disambiguation, List<AliasEntryDto> aliases) {}
    record AliasGroupDto(String group, List<FieldDto> fields) {}

    private final FmCanonicalFieldRepository canonicalFieldRepo;
    private final FmAliasRepository          aliasRepo;
    private final FmBlocklistRepository      blocklistRepo;
    private final FmSuggestionRepository     suggestionRepo;
    private final AuditLogService            auditService;

    public FieldMappingController(FmCanonicalFieldRepository canonicalFieldRepo,
                                  FmAliasRepository aliasRepo,
                                  FmBlocklistRepository blocklistRepo,
                                  FmSuggestionRepository suggestionRepo,
                                  AuditLogService auditService) {
        this.canonicalFieldRepo = canonicalFieldRepo;
        this.aliasRepo          = aliasRepo;
        this.blocklistRepo      = blocklistRepo;
        this.suggestionRepo     = suggestionRepo;
        this.auditService       = auditService;
    }

    @GetMapping("/alias-groups")
    public List<AliasGroupDto> aliasGroups() {
        List<FmCanonicalField> fields    = canonicalFieldRepo.findAllByOrderByGroupSortAscFieldSortAsc();
        List<FmAlias>          allAliases = aliasRepo.findAllByOrderByCanonicalFieldIdAscAliasSortAsc();

        Map<Integer, List<FmAlias>> byFieldId = new HashMap<>();
        for (FmAlias a : allAliases) {
            byFieldId.computeIfAbsent(a.getCanonicalFieldId(), k -> new ArrayList<>()).add(a);
        }

        Map<String, List<FmCanonicalField>> byGroup = new LinkedHashMap<>();
        for (FmCanonicalField f : fields) {
            byGroup.computeIfAbsent(f.getGroupName(), k -> new ArrayList<>()).add(f);
        }

        List<AliasGroupDto> result = new ArrayList<>();
        for (Map.Entry<String, List<FmCanonicalField>> entry : byGroup.entrySet()) {
            List<FieldDto> fieldDtos = new ArrayList<>();
            for (FmCanonicalField f : entry.getValue()) {
                List<AliasEntryDto> aliasDtos = byFieldId.getOrDefault(f.getId(), List.of())
                    .stream()
                    .map(a -> new AliasEntryDto(a.getId(), a.getAliasText(), a.getTier(), a.getBank()))
                    .toList();
                fieldDtos.add(new FieldDto(f.getId(), f.getCanonical(), f.getLpMasterField(), f.getDisambiguation(), aliasDtos));
            }
            result.add(new AliasGroupDto(entry.getKey(), fieldDtos));
        }
        return result;
    }

    @GetMapping("/canonical-fields")
    public List<Map<String, String>> canonicalFields() {
        return canonicalFieldRepo.findAllByOrderByGroupSortAscFieldSortAsc().stream()
            .map(f -> Map.of(
                "value", f.getCanonical(),
                "label", f.getGroupName() + " › " + f.getCanonical()
            ))
            .toList();
    }

    @GetMapping("/blocklist")
    public List<FmBlocklistEntry> blocklist() {
        return blocklistRepo.findAllByOrderByIdAsc();
    }

    @GetMapping("/suggestions")
    public List<FmSuggestion> suggestions() {
        return suggestionRepo.findAllByOrderByCreatedAtDesc();
    }

    @PostMapping("/aliases")
    public ResponseEntity<AliasEntryDto> createAlias(@RequestBody Map<String, Object> body,
                                                     HttpServletRequest request) {
        Integer canonicalFieldId = (Integer) body.get("canonicalFieldId");
        String  text = (String) body.get("text");
        if (canonicalFieldId == null || text == null) {
            return ResponseEntity.badRequest().build();
        }
        String  tier = body.containsKey("tier") ? (String) body.get("tier") : "Bank";
        String  bank = (String) body.get("bank");

        List<FmAlias> existing = aliasRepo.findByCanonicalFieldIdOrderByAliasSortAsc(canonicalFieldId);
        int nextSort = existing.isEmpty() ? 1 : existing.get(existing.size() - 1).getAliasSort() + 1;

        FmAlias alias = new FmAlias();
        alias.setCanonicalFieldId(canonicalFieldId);
        alias.setAliasSort(nextSort);
        alias.setAliasText(text);
        alias.setTier(tier);
        alias.setBank(bank);
        FmAlias saved = aliasRepo.save(alias);

        String canonical = canonicalFieldRepo.findById(canonicalFieldId)
            .map(FmCanonicalField::getCanonical).orElse("field " + canonicalFieldId);
        auditService.log("Field Mapping Change", "FM Alias Added: \"" + text + "\" → " + canonical,
            null, auditService.extractIp(request));

        return ResponseEntity.status(201).body(new AliasEntryDto(saved.getId(), saved.getAliasText(), saved.getTier(), saved.getBank()));
    }

    @DeleteMapping("/aliases/{id}")
    public ResponseEntity<Void> deleteAlias(@PathVariable int id, HttpServletRequest request) {
        FmAlias alias = aliasRepo.findById(id).orElse(null);
        if (alias == null) return ResponseEntity.notFound().build();
        String text = alias.getAliasText();
        aliasRepo.deleteById(id);
        auditService.log("Field Mapping Change", "FM Alias Removed: \"" + text + "\"",
            null, auditService.extractIp(request));
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/aliases/{id}")
    public ResponseEntity<AliasEntryDto> updateAlias(@PathVariable int id,
                                                     @RequestBody Map<String, String> body,
                                                     HttpServletRequest request) {
        FmAlias alias = aliasRepo.findById(id).orElse(null);
        if (alias == null) return ResponseEntity.notFound().build();
        String oldText = alias.getAliasText();
        if (body.containsKey("text")) alias.setAliasText(body.get("text"));
        if (body.containsKey("bank")) alias.setBank(body.get("bank"));
        FmAlias saved = aliasRepo.save(alias);
        auditService.log("Field Mapping Change", "FM Alias Updated: \"" + oldText + "\" → \"" + saved.getAliasText() + "\"",
            null, auditService.extractIp(request));
        return ResponseEntity.ok(new AliasEntryDto(saved.getId(), saved.getAliasText(), saved.getTier(), saved.getBank()));
    }

    @PostMapping("/suggestions")
    public ResponseEntity<FmSuggestion> suggest(@RequestBody Map<String, String> body) {
        FmSuggestion s = new FmSuggestion();
        s.setExtractedHeader(body.get("extractedHeader"));
        s.setCanonicalField(body.get("canonicalField"));
        s.setSuggestedBy(body.get("suggestedBy"));
        return ResponseEntity.status(201).body(suggestionRepo.save(s));
    }
}
