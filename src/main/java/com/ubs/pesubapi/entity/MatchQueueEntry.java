package com.ubs.pesubapi.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "match_queue_entries")
public class MatchQueueEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "submission_id", nullable = false)
    private Integer submissionId;

    @Column(name = "facility_id", nullable = false)
    private Integer facilityId;

    @Column(name = "row_index", nullable = false)
    private int rowIndex;

    @Column(name = "extracted_name")
    private String extractedName;

    /** The facility LP record this row resolved to (lp_records), cleared when records are replaced. */
    @Column(name = "matched_lp_id")
    private Integer matchedLpId;

    /**
     * The LP Master record the match proposes — a different thing from {@link #matchedLpId}, and
     * the anchor for parent/child routing. Always the matched child/feeder, never its parent.
     */
    @Column(name = "matched_lp_master_id")
    private Integer matchedLpMasterId;

    @Column(name = "matched_lp_name")
    private String matchedLpName;

    @Column(name = "match_score")
    private Integer matchScore;

    @Column(name = "decision", nullable = false)
    private String decision = "pending";

    @Column(name = "agent_parent")
    private String agentParent;

    @Column(name = "master_parent")
    private String masterParent;

    @Column(name = "master_name_override")
    private String masterNameOverride;

    @Column(name = "is_new", nullable = false)
    private boolean isNew;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reasons", columnDefinition = "jsonb")
    private List<String> reasons;

    /** Ranked candidate breakdown (normalised agent name, top-5 JW/Lev/combined scores, band) — §6.5. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "match_details", columnDefinition = "jsonb")
    private JsonNode matchDetails;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    public Integer getId()             { return id; }
    public Integer getSubmissionId()   { return submissionId; }
    public Integer getFacilityId()     { return facilityId; }
    public int getRowIndex()           { return rowIndex; }
    public String getExtractedName()   { return extractedName; }
    public Integer getMatchedLpId()    { return matchedLpId; }
    public Integer getMatchedLpMasterId() { return matchedLpMasterId; }
    public String getMatchedLpName()   { return matchedLpName; }
    public Integer getMatchScore()     { return matchScore; }
    public String getDecision()        { return decision; }
    public String getAgentParent()         { return agentParent; }
    public String getMasterParent()        { return masterParent; }
    public String getMasterNameOverride()  { return masterNameOverride; }
    public boolean isNew()             { return isNew; }
    public List<String> getReasons()   { return reasons; }
    public JsonNode getMatchDetails()  { return matchDetails; }
    public LocalDateTime getCreatedAt(){ return createdAt; }
    public LocalDateTime getUpdatedAt(){ return updatedAt; }

    public void setSubmissionId(Integer v)    { this.submissionId = v; }
    public void setFacilityId(Integer v)      { this.facilityId = v; }
    public void setRowIndex(int v)            { this.rowIndex = v; }
    public void setExtractedName(String v)    { this.extractedName = v; }
    public void setMatchedLpId(Integer v)     { this.matchedLpId = v; }
    public void setMatchedLpMasterId(Integer v) { this.matchedLpMasterId = v; }
    public void setMatchedLpName(String v)    { this.matchedLpName = v; }
    public void setMatchScore(Integer v)      { this.matchScore = v; }
    public void setDecision(String v)         { this.decision = v; }
    public void setAgentParent(String v)       { this.agentParent = v; }
    public void setMasterParent(String v)      { this.masterParent = v; }
    public void setMasterNameOverride(String v){ this.masterNameOverride = v; }
    public void setNew(boolean v)             { this.isNew = v; }
    public void setReasons(List<String> v)    { this.reasons = v; }
    public void setMatchDetails(JsonNode v)   { this.matchDetails = v; }
}
