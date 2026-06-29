package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.IntegrationTestBase;
import com.ubs.pesubapi.dto.ExtractionResponse;
import com.ubs.pesubapi.entity.Facility;
import com.ubs.pesubapi.entity.Submission;
import com.ubs.pesubapi.entity.SubmissionExtraction;
import com.ubs.pesubapi.repository.AuditLogRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.SubmissionExtractionRepository;
import com.ubs.pesubapi.repository.SubmissionRepository;
import com.ubs.pesubapi.service.ExtractionClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The operator-forced Agent BB template (V1_5 migration) must round-trip through the real
 * Postgres schema, and the re-extract endpoint must accept an optional template override body.
 */
@TestPropertySource(properties = "app.uploads.path=target/test-uploads")
class SubmissionForcedTemplateTest extends IntegrationTestBase {

    @Autowired MockMvc                        mvc;
    @Autowired SubmissionExtractionRepository extractionRepo;
    @Autowired SubmissionRepository           submissionRepo;
    @Autowired FacilityRepository             facilityRepo;
    @Autowired AuditLogRepository             auditLogRepo;
    @MockitoBean ExtractionClientService      extractionClient;

    private int facilityId;
    private int submissionId;

    @BeforeEach
    void setup() {
        reset(extractionClient);
        when(extractionClient.extract(anyString(), any(Path.class), nullable(String.class),
                nullable(Integer.class), nullable(String.class), nullable(Integer.class),
                nullable(String.class), anyList(), anyBoolean(), anyList()))
            .thenReturn(emptyExtraction());

        extractionRepo.deleteAll();
        submissionRepo.deleteAll();
        auditLogRepo.deleteAll();
        facilityRepo.deleteAll();

        Facility f = new Facility();
        f.setName("Audax Direct Lending VII");
        f.setAgentBank("Audax");
        facilityId = facilityRepo.save(f).getId();

        Submission s = new Submission();
        s.setFacilityId(facilityId);
        s.setAgentBank("Audax");
        s.setPeriodMonth("2026-05");
        s.setFileName("Agent-BB-Audax-Fund-VII.xlsx");
        s.setStatus("Review");
        submissionId = submissionRepo.save(s).getId();
    }

    @Test
    void forcedTemplate_roundTripsThroughPostgres() {
        SubmissionExtraction e = new SubmissionExtraction();
        e.setSubmissionId(submissionId);
        e.setForcedTemplate("Audax Fund VII");
        e.setTotalRows(0);
        e.setFlaggedCount(0);
        extractionRepo.save(e);

        SubmissionExtraction reloaded = extractionRepo.findBySubmissionId(submissionId).orElseThrow();
        assertThat(reloaded.getForcedTemplate()).isEqualTo("Audax Fund VII");
    }

    @Test
    void forcedTemplate_nullByDefault_meansAutoDetect() {
        SubmissionExtraction e = new SubmissionExtraction();
        e.setSubmissionId(submissionId);
        e.setTotalRows(0);
        e.setFlaggedCount(0);
        extractionRepo.save(e);

        SubmissionExtraction reloaded = extractionRepo.findBySubmissionId(submissionId).orElseThrow();
        assertThat(reloaded.getForcedTemplate()).isNull();
    }

    @Test
    void reextract_acceptsTemplateOverrideBody_butReturns404ForUnknownSubmission() throws Exception {
        // Unknown submission short-circuits before reaching the (test-unreachable) extraction
        // service, proving the new optional @RequestBody parses without error.
        mvc.perform(post("/api/submissions/{id}/reextract", 999999)
                .contentType("application/json")
                .content("{\"templateName\":\"Audax Fund VII\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void upload_persistsForcedTemplate_andRemapReusesIt() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "Agent-BB-KKR-Ascendant-Fund.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            new byte[] { 1, 2, 3 });

        mvc.perform(multipart("/api/submissions")
                .file(file)
                .param("facilityId", String.valueOf(facilityId))
                .param("agentBank", "Goldman Sachs Bank USA")
                .param("periodMonth", "2026-06")
                .param("forceTemplate", " KKR Ascendant Fund "))
            .andExpect(status().isCreated());

        SubmissionExtraction stored = extractionRepo.findAll().stream()
            .filter(e -> e.getForcedTemplate() != null)
            .findFirst().orElseThrow();
        assertThat(stored.getForcedTemplate()).isEqualTo("KKR Ascendant Fund");

        mvc.perform(post("/api/submissions/{id}/remap", stored.getSubmissionId())
                .contentType("application/json")
                .content("{\"extractedHeader\":\"Parent / Sponsor\",\"canonical\":\"Parent / Sponsor\"}"))
            .andExpect(status().isOk());

        verify(extractionClient, times(2)).extract(anyString(), any(Path.class),
            eq("Borrowing Base"), eq(9), eq("Goldman Sachs Bank USA"), eq(1),
            eq("KKR Ascendant Fund"), eq(List.of()), eq(false), anyList());
    }

    @Test
    void upload_forceTemplateOverridesAgentBankTemplateHints() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "Agent-BB-KKR-Ascendant-Fund.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            new byte[] { 1, 2, 3 });

        mvc.perform(multipart("/api/submissions")
                .file(file)
                .param("facilityId", String.valueOf(facilityId))
                .param("agentBank", "Wells Fargo (Blue Owl GP Stakes V)")
                .param("periodMonth", "2026-06")
                .param("forceTemplate", "KKR Ascendant Fund"))
            .andExpect(status().isCreated());

        verify(extractionClient).extract(anyString(), any(Path.class),
            eq("Borrowing Base"), eq(9), eq("Wells Fargo (Blue Owl GP Stakes V)"), eq(1),
            eq("KKR Ascendant Fund"), eq(List.of()), eq(false), anyList());
    }

    @Test
    void upload_exposesDbBackedAdvanceRateAndBorrowingBaseKeysToUiRows() throws Exception {
        when(extractionClient.extract(anyString(), any(Path.class), nullable(String.class),
                nullable(Integer.class), nullable(String.class), nullable(Integer.class),
                nullable(String.class), anyList(), anyBoolean(), anyList()))
            .thenReturn(kkrExtractionWithDbBackedKeys());

        MockMultipartFile file = new MockMultipartFile(
            "file",
            "Agent-BB-KKR-Ascendant-Fund.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            new byte[] { 1, 2, 3 });

        String created = mvc.perform(multipart("/api/submissions")
                .file(file)
                .param("facilityId", String.valueOf(facilityId))
                .param("agentBank", "JP Morgan Chase Bank NA")
                .param("periodMonth", "2026-06")
                .param("forceTemplate", "JP Morgan (KKR Ascendant Fund)"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        int createdSubmissionId = com.jayway.jsonpath.JsonPath.read(created, "$.id");

        mvc.perform(get("/api/submissions/{id}/extracted-lps", createdSubmissionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("Acme Pension Fund"))
            .andExpect(jsonPath("$[0].agentRate").value("90.0%"))
            .andExpect(jsonPath("$[0].agentBB").value("315,000"))
            .andExpect(jsonPath("$[0].agentBBFmt").value("$315,000"));
    }

    @Test
    void remap_fallsBackToRecognizedStructuralTemplateVersion() throws Exception {
        Submission s = submissionRepo.findById(submissionId).orElseThrow();
        s.setAgentBank("Goldman Sachs Bank USA");
        s.setFilePath("target/test-uploads/Agent-BB-Petershill IV.xlsx");
        submissionRepo.save(s);

        SubmissionExtraction e = new SubmissionExtraction();
        e.setSubmissionId(submissionId);
        e.setTemplateFormat("GOLDMAN_SACHS");
        e.setTemplateVersion("Petershill IV");
        e.setTotalRows(0);
        e.setFlaggedCount(0);
        extractionRepo.save(e);

        mvc.perform(post("/api/submissions/{id}/remap", submissionId)
                .contentType("application/json")
                .content("{\"extractedHeader\":\"Parent / Sponsor\",\"canonical\":\"Parent / Sponsor\"}"))
            .andExpect(status().isOk());

        verify(extractionClient).extract(anyString(), any(Path.class),
            eq("Borrowing Base"), eq(10), eq("Goldman Sachs Bank USA"), eq(1),
            eq("Petershill IV"));
    }

    private ExtractionResponse emptyExtraction() {
        return new ExtractionResponse(
            new ExtractionResponse.TemplateInfo("GOLDMAN_SACHS", "Petershill IV", 8, "LP Data Extract"),
            List.of(),
            0,
            List.of(),
            List.of("Parent / Sponsor"));
    }

    private ExtractionResponse kkrExtractionWithDbBackedKeys() {
        return new ExtractionResponse(
            new ExtractionResponse.TemplateInfo("JPM", "KKR Ascendant Fund", 9, "Borrowing Base"),
            List.of(new ExtractionResponse.ExtractedRecord(
                5,
                Map.of(
                    "INVESTOR_NAME", new ExtractionResponse.FieldValue("Acme Pension Fund", 1.0, "Investor"),
                    "COMMITMENT", new ExtractionResponse.FieldValue("500,000", 1.0, "Total Commitment"),
                    "UNCALLED", new ExtractionResponse.FieldValue("350,000", 1.0, "Unfunded Commitment"),
                    "ADVANCE_RATE", new ExtractionResponse.FieldValue("90%", 1.0, "Advance Rate"),
                    "BORROWING_BASE", new ExtractionResponse.FieldValue("315,000", 1.0, "Borrowing Base")
                ),
                false,
                List.of(),
                null)),
            0,
            List.of(),
            List.of());
    }
}
