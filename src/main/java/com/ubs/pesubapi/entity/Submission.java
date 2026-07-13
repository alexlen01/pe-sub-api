package com.ubs.pesubapi.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;
import java.time.LocalDateTime;

@Entity
@Table(name = "submissions")
public class Submission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "facility_id", nullable = false)
    private Integer facilityId;

    @Column(name = "agent_bank", nullable = false)
    private String agentBank;

    @Column(name = "period_month", nullable = false)
    private String periodMonth;

    @Column(nullable = false)
    private String status = "Processing";

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "uploaded_by")
    private Integer uploadedBy;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** Stable uuName of the analyst who uploaded/owns the submission (ownership key, set at upload). */
    @Column(name = "owner_uu_name")
    private String ownerUuName;

    /** Display name of the owner captured at upload (shown without needing a user directory). */
    @Column(name = "owner_name")
    private String ownerName;

    /** Stable identity of the operator who submitted the Shadow BB for independent review (maker). */
    @Column(name = "submitted_by")
    private String submittedBy;

    /** Stable identity of the manager who accepted or rejected the review (checker). */
    @Column(name = "reviewed_by")
    private String reviewedBy;

    /** Reviewer rationale; required on rejection. */
    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;

    /** Optimistic-lock token: Hibernate bumps it on every write; a stale writer is rejected. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "wizard_step", nullable = false)
    private int wizardStep = 1;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shadow_bb_overrides", columnDefinition = "jsonb")
    private JsonNode shadowBbOverrides;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    public Integer       getId()                 { return id; }
    public Integer       getFacilityId()         { return facilityId; }
    public String        getAgentBank()          { return agentBank; }
    public String        getPeriodMonth()        { return periodMonth; }
    public String        getStatus()             { return status; }
    public String        getFileName()           { return fileName; }
    public String        getFilePath()           { return filePath; }
    public Integer       getUploadedBy()         { return uploadedBy; }
    public String        getNotes()              { return notes; }
    public String        getOwnerUuName()        { return ownerUuName; }
    public String        getOwnerName()          { return ownerName; }
    public String        getSubmittedBy()        { return submittedBy; }
    public String        getReviewedBy()         { return reviewedBy; }
    public String        getReviewNote()         { return reviewNote; }
    public Long          getVersion()            { return version; }
    public int           getWizardStep()         { return wizardStep; }
    public JsonNode      getShadowBbOverrides()  { return shadowBbOverrides; }
    public LocalDateTime getCreatedAt()          { return createdAt; }
    public LocalDateTime getUpdatedAt()          { return updatedAt; }

    public void setFacilityId(Integer v)        { this.facilityId        = v; }
    public void setAgentBank(String v)          { this.agentBank         = v; }
    public void setPeriodMonth(String v)        { this.periodMonth       = v; }
    public void setStatus(String v)             { this.status            = v; }
    public void setFileName(String v)           { this.fileName          = v; }
    public void setFilePath(String v)           { this.filePath          = v; }
    public void setUploadedBy(Integer v)        { this.uploadedBy        = v; }
    public void setNotes(String v)              { this.notes             = v; }
    public void setOwnerUuName(String v)        { this.ownerUuName       = v; }
    public void setOwnerName(String v)          { this.ownerName         = v; }
    public void setSubmittedBy(String v)        { this.submittedBy       = v; }
    public void setReviewedBy(String v)         { this.reviewedBy        = v; }
    public void setReviewNote(String v)         { this.reviewNote        = v; }
    public void setWizardStep(int v)            { this.wizardStep        = v; }
    public void setShadowBbOverrides(JsonNode v){ this.shadowBbOverrides = v; }
}
