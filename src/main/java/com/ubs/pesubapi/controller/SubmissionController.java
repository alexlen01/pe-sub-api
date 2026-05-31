package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.entity.Submission;
import com.ubs.pesubapi.repository.FacilityRepository;
import com.ubs.pesubapi.repository.SubmissionRepository;
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
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/submissions")
public class SubmissionController {

    private final SubmissionRepository submissions;
    private final FacilityRepository   facilities;

    @Value("${app.uploads.path:C:/Users/alexl/apps/pe-sub/uploads}")
    private String uploadsPath;

    public SubmissionController(SubmissionRepository submissions, FacilityRepository facilities) {
        this.submissions = submissions;
        this.facilities  = facilities;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> create(
            @RequestParam("facilityId")  int           facilityId,
            @RequestParam("agentBank")   String        agentBank,
            @RequestParam("periodMonth") String        periodMonth,
            @RequestParam("file")        MultipartFile file
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

        return ResponseEntity.status(201).body(submissions.save(sub));
    }

    @GetMapping
    public List<Submission> list(@RequestParam(required = false) Integer facilityId) {
        return facilityId != null
            ? submissions.findByFacilityIdOrderByCreatedAtDesc(facilityId)
            : submissions.findAllByOrderByCreatedAtDesc();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Submission> get(@PathVariable int id) {
        return submissions.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
