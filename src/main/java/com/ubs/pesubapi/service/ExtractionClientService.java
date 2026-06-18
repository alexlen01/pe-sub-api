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
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("facilityId", facilityId);
            body.add("file", new FileSystemResource(Objects.requireNonNull(filePath)));

            String aliasJson = aliasConfigBuilder.buildJson();
            if (aliasJson    != null) body.add("aliasConfig",   aliasJson);
            if (sheetNameHint != null) body.add("sheetNameHint", sheetNameHint);
            if (headerRowHint != null) body.add("headerRowHint", String.valueOf(headerRowHint));
            // Only send a span > 1 (stacked header); 1 is the engine default.
            if (headerRowSpan != null && headerRowSpan > 1) body.add("headerRowSpan", String.valueOf(headerRowSpan));

            String classificationJson = classificationConfigBuilder.buildJson(agentBank);
            if (classificationJson != null) body.add("classificationConfig", classificationJson);

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
