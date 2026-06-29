package com.ubs.pesubapi.service;

import com.ubs.pesubapi.dto.ExtractionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

@Service
public class ExtractionClientService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionClientService.class);

    private final RestClient                 extractionClient;
    private final AliasConfigBuilder          aliasConfigBuilder;
    private final ClassificationConfigBuilder classificationConfigBuilder;

    public ExtractionClientService(RestClient peSubExtractionClient,
                                   AliasConfigBuilder aliasConfigBuilder,
                                   ClassificationConfigBuilder classificationConfigBuilder) {
        this.extractionClient            = peSubExtractionClient;
        this.aliasConfigBuilder          = aliasConfigBuilder;
        this.classificationConfigBuilder = classificationConfigBuilder;
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
