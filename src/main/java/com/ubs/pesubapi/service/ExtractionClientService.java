package com.ubs.pesubapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubs.pesubapi.dto.ExtractionResponse;
import com.ubs.pesubapi.dto.ResolvedTemplate;
import com.ubs.pesubapi.dto.WorkbookSignals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ExtractionClientService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionClientService.class);

    private final RestClient                 extractionClient;
    private final AliasConfigBuilder          aliasConfigBuilder;
    private final ClassificationConfigBuilder classificationConfigBuilder;
    private final ObjectMapper                mapper;

    public ExtractionClientService(RestClient peSubExtractionClient,
                                   AliasConfigBuilder aliasConfigBuilder,
                                   ClassificationConfigBuilder classificationConfigBuilder,
                                   ObjectMapper mapper) {
        this.extractionClient            = peSubExtractionClient;
        this.aliasConfigBuilder          = aliasConfigBuilder;
        this.classificationConfigBuilder = classificationConfigBuilder;
        this.mapper                      = mapper;
    }

    /**
     * Asks pe-sub-extraction to parse the workbook and return raw structural signals (sheet
     * names + first rows) so pe-sub-api can recognise the template before extraction.
     * @return WorkbookSignals, or null if pe-sub-extraction is unreachable
     */
    public WorkbookSignals inspect(String facilityId, Path filePath) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(Objects.requireNonNull(filePath)));
            return extractionClient.post()
                .uri("/api/inspect")
                .contentType(Objects.requireNonNull(MediaType.MULTIPART_FORM_DATA))
                .body(body)
                .retrieve()
                .body(WorkbookSignals.class);
        } catch (RestClientException e) {
            log.warn("pe-sub-extraction /inspect unreachable for facility {}: {}", facilityId, e.getMessage());
            return null;
        }
    }

    /**
     * Inspects a multipart upload directly (used by template profiling / self-adoption).
     * {@code rows} caps the per-sheet depth so the profiler can see LP-category banner rows.
     * @return WorkbookSignals, or null if pe-sub-extraction is unreachable
     */
    public WorkbookSignals inspect(MultipartFile file, int rows) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", file.getResource());
            return extractionClient.post()
                .uri("/api/inspect?rows={rows}", rows)
                .contentType(Objects.requireNonNull(MediaType.MULTIPART_FORM_DATA))
                .body(body)
                .retrieve()
                .body(WorkbookSignals.class);
        } catch (RestClientException e) {
            log.warn("pe-sub-extraction /inspect (profile) unreachable: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Sends the file to pe-sub-extraction for parsing against a recognised {@link ResolvedTemplate}.
     * The engine performs no recognition: sheet(s), header row + span, the LP-category group map
     * and skip keywords all come from the resolved definition. aliasConfig still derives from the
     * Field Mapping Dictionary (optionally agent-specific). forward=false so the engine does not
     * re-call this service's ingest endpoint.
     * @return ExtractionResponse, or null if pe-sub-extraction is unreachable
     */
    public ExtractionResponse extractResolved(String facilityId, Path filePath,
                                              String agentBank, ResolvedTemplate t) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("facilityId", facilityId);
            body.add("file", new FileSystemResource(Objects.requireNonNull(filePath)));

            String aliasJson = aliasConfigBuilder.buildJson(agentBank);
            if (aliasJson != null) body.add("aliasConfig", aliasJson);

            if (t.sheetName() != null) body.add("sheetNameHint", t.sheetName());
            if (t.headerRowIndex() != null) body.add("headerRowHint", String.valueOf(t.headerRowIndex()));
            if (t.headerRowSpan() != null && t.headerRowSpan() > 1) body.add("headerRowSpan", String.valueOf(t.headerRowSpan()));

            String classificationJson = classificationJson(t.groupMap());
            if (classificationJson != null) body.add("classificationConfig", classificationJson);

            if (agentBank != null && !agentBank.isBlank()) body.add("agentBank", agentBank);
            if (t.sheetNames() != null && !t.sheetNames().isEmpty()) t.sheetNames().forEach(s -> body.add("sheetNames", s));
            if (t.autoDiscoverTabs()) body.add("autoDiscoverTabs", "true");
            if (t.skipRowKeywords() != null && !t.skipRowKeywords().isEmpty()) {
                t.skipRowKeywords().forEach(k -> body.add("skipRowKeywords", k));
            }

            log.info("Calling pe-sub-extraction /extract facilityId={} file='{}' recognized={} template='{}' sheet='{}' headerRow={} span={} sheetNames={} autoDiscover={} groups={} aliasConfig={}",
                facilityId, filePath != null ? filePath.getFileName() : null,
                t.recognized(), t.templateName(), t.sheetName(), t.headerRowIndex(), t.headerRowSpan(),
                t.sheetNames(), t.autoDiscoverTabs(), t.groupMap() != null ? t.groupMap().size() : 0,
                aliasJson != null ? "present" : "absent");

            return extractionClient.post()
                .uri("/api/extract?forward=false")
                .contentType(Objects.requireNonNull(MediaType.MULTIPART_FORM_DATA))
                .body(body)
                .retrieve()
                .body(ExtractionResponse.class);
        } catch (RestClientException e) {
            log.warn("pe-sub-extraction /extract unreachable for facility {}: {}", facilityId, e.getMessage());
            return null;
        }
    }

    private String classificationJson(Map<String, String> groupMap) {
        if (groupMap == null || groupMap.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(groupMap);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise group map — extraction will use standard classification values: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Sends the uploaded file to pe-sub-extraction for parsing.
     * Passes forward=false so pe-sub-extraction does not re-call pe-sub-api/lps/ingest.
     * Passes aliasConfig (JSON from the DB Field Mapping Dictionary) so HeaderMatcher
     * uses live user-configured aliases instead of its hardcoded fallback map.
     * Passes classificationConfig (per-agent group-header → Agent LP Classification map)
     * so the extraction engine can recognise classification section rows.
     *
     * @return ExtractionResponse, or null if pe-sub-extraction is unreachable
     */
    public ExtractionResponse extract(String facilityId, Path filePath) {
        return extract(facilityId, filePath, null, null, null);
    }

    public ExtractionResponse extract(String facilityId, Path filePath,
                                      String sheetNameHint, Integer headerRowHint) {
        return extract(facilityId, filePath, sheetNameHint, headerRowHint, null);
    }

    public ExtractionResponse extract(String facilityId, Path filePath,
                                      String sheetNameHint, Integer headerRowHint,
                                      String agentBank) {
        return extract(facilityId, filePath, sheetNameHint, headerRowHint, agentBank, null);
    }

    public ExtractionResponse extract(String facilityId, Path filePath,
                                      String sheetNameHint, Integer headerRowHint,
                                      String agentBank, Integer headerRowSpan) {
        return extract(facilityId, filePath, sheetNameHint, headerRowHint,
            agentBank, headerRowSpan, null);
    }

    public ExtractionResponse extract(String facilityId, Path filePath,
                                      String sheetNameHint, Integer headerRowHint,
                                      String agentBank, Integer headerRowSpan,
                                      String forceTemplate) {
        return extract(facilityId, filePath, sheetNameHint, headerRowHint, agentBank,
            headerRowSpan, forceTemplate, null, false);
    }

    /**
     * Multi-tab extraction: pass an ordered list of sheet names (named sleeves) or set
     * autoDiscoverTabs=true so the engine scans every sheet in the workbook.
     * When sheetNames is non-empty the engine extracts each sheet in order and tags
     * each LP record with the sheet name as fundSleeve.
     */
    public ExtractionResponse extract(String facilityId, Path filePath,
                                      String sheetNameHint, Integer headerRowHint,
                                      String agentBank, Integer headerRowSpan,
                                      String forceTemplate,
                                      List<String> sheetNames,
                                      boolean autoDiscoverTabs) {
        return extract(facilityId, filePath, sheetNameHint, headerRowHint, agentBank,
            headerRowSpan, forceTemplate, sheetNames, autoDiscoverTabs, List.of());
    }

    public ExtractionResponse extract(String facilityId, Path filePath,
                                      String sheetNameHint, Integer headerRowHint,
                                      String agentBank, Integer headerRowSpan,
                                      String forceTemplate,
                                      List<String> sheetNames,
                                      boolean autoDiscoverTabs,
                                      List<String> skipRowKeywords) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("facilityId", facilityId);
            body.add("file", new FileSystemResource(Objects.requireNonNull(filePath)));

            String aliasJson = aliasConfigBuilder.buildJson(agentBank);
            if (aliasJson    != null) body.add("aliasConfig",   aliasJson);
            if (sheetNameHint != null) body.add("sheetNameHint", sheetNameHint);
            if (headerRowHint != null) body.add("headerRowHint", String.valueOf(headerRowHint));
            if (headerRowSpan != null && headerRowSpan > 1) body.add("headerRowSpan", String.valueOf(headerRowSpan));

            String classificationJson = classificationConfigBuilder.buildJson(agentBank, forceTemplate);
            if (classificationJson != null) body.add("classificationConfig", classificationJson);

            if (agentBank != null && !agentBank.isBlank()) body.add("agentBank", agentBank);
            if (forceTemplate != null && !forceTemplate.isBlank()) body.add("forceTemplate", forceTemplate);

            if (sheetNames != null && !sheetNames.isEmpty()) {
                sheetNames.forEach(s -> body.add("sheetNames", s));
            }
            if (autoDiscoverTabs) {
                body.add("autoDiscoverTabs", "true");
            }
            if (skipRowKeywords != null && !skipRowKeywords.isEmpty()) {
                skipRowKeywords.forEach(k -> body.add("skipRowKeywords", k));
            }

            log.info("Calling pe-sub-extraction facilityId={} file='{}' agentBank='{}' sheetHint='{}' headerRowHint={} headerRowSpan={} forcedTemplate='{}' sheetNames={} autoDiscoverTabs={} aliasConfig={} classificationConfig={}",
                facilityId,
                filePath != null ? filePath.getFileName() : null,
                agentBank, sheetNameHint, headerRowHint, headerRowSpan, forceTemplate,
                sheetNames, autoDiscoverTabs,
                aliasJson != null ? "present" : "absent",
                classificationJson != null ? "present" : "absent");

            return extractionClient.post()
                .uri("/api/extract?forward=false")
                .contentType(Objects.requireNonNull(MediaType.MULTIPART_FORM_DATA))
                .body(body)
                .retrieve()
                .body(ExtractionResponse.class);
        } catch (RestClientException e) {
            log.warn("pe-sub-extraction unreachable for facility {}: {}", facilityId, e.getMessage());
            return null;
        }
    }
}
