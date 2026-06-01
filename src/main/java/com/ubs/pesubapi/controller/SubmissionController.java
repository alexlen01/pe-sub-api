package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.entity.Submission;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.SubmissionRepository;
import com.ubs.pesubapi.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/submissions")
public class SubmissionController {

    record SubmissionDto(
        Integer id, Integer facilityId, String facilityName,
        String agentBank, String periodMonth, String status,
        String fileName, Integer uploadedBy,
        LocalDateTime createdAt, LocalDateTime updatedAt
    ) {}

    private final SubmissionRepository submissions;
    private final FacilityRepository   facilities;
    private final AuditLogService      auditService;

    @Value("${app.uploads.path:C:/Users/alexl/apps/pe-sub/uploads}")
    private String uploadsPath;

    public SubmissionController(SubmissionRepository submissions, FacilityRepository facilities,
                                AuditLogService auditService) {
        this.submissions  = submissions;
        this.facilities   = facilities;
        this.auditService = auditService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> create(
            @RequestParam("facilityId")  int           facilityId,
            @RequestParam("agentBank")   String        agentBank,
            @RequestParam("periodMonth") String        periodMonth,
            @RequestParam("file")        MultipartFile file,
            HttpServletRequest request
    ) throws IOException {
        if (facilities.findById(facilityId).isEmpty()) {
            return ResponseEntity.badRequest().body("Facility " + facilityId + " not found.");
        }

        String original   = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.xlsx";
        String storedName = UUID.randomUUID() + "_" + original;
        Path   dir        = Paths.get(uploadsPath);
        Files.createDirectories(dir);
        Files.copy(file.getInputStream(), dir.resolve(storedName), StandardCopyOption.REPLACE_EXISTING);

        Submission sub = new Submission();
        sub.setFacilityId(facilityId);
        sub.setAgentBank(agentBank);
        sub.setPeriodMonth(periodMonth);
        sub.setFileName(original);
        sub.setFilePath(dir.resolve(storedName).toString());
        Submission saved = submissions.save(sub);

        String detail = periodMonth + " · " + original + " · " + agentBank;
        auditService.log("Upload", detail, facilityId, "J. Smith", auditService.extractIp(request));

        return ResponseEntity.status(201).body(saved);
    }

    @GetMapping
    public List<SubmissionDto> list(@RequestParam(required = false) Integer facilityId) {
        List<Submission> subs = facilityId != null
            ? submissions.findByFacilityIdOrderByCreatedAtDesc(facilityId)
            : submissions.findAllByOrderByCreatedAtDesc();

        Set<Integer> ids = subs.stream().map(Submission::getFacilityId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Integer, String> nameById = new HashMap<>();
        facilities.findAllById(ids).forEach(f -> nameById.put(f.getId(), f.getName()));

        return subs.stream().map(s -> new SubmissionDto(
            s.getId(), s.getFacilityId(), nameById.getOrDefault(s.getFacilityId(), "—"),
            s.getAgentBank(), s.getPeriodMonth(), s.getStatus(),
            s.getFileName(), s.getUploadedBy(), s.getCreatedAt(), s.getUpdatedAt()
        )).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Submission> get(@PathVariable int id) {
        return submissions.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
