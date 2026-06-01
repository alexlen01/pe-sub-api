package com.ubs.pesubapi.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "fm_suggestions")
public class FmSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "extracted_header", nullable = false)
    private String extractedHeader;

    @Column(name = "canonical_field", nullable = false)
    private String canonicalField;

    @Column(name = "suggested_by")
    private String suggestedBy;

    @Column(nullable = false)
    private String source = "User";

    private Integer confidence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }

    public Integer       getId()              { return id; }
    public String        getExtractedHeader() { return extractedHeader; }
    public String        getCanonicalField()  { return canonicalField; }
    public String        getSuggestedBy()     { return suggestedBy; }
    public String        getSource()          { return source; }
    public Integer       getConfidence()      { return confidence; }
    public LocalDateTime getCreatedAt()       { return createdAt; }

    public void setExtractedHeader(String v) { this.extractedHeader = v; }
    public void setCanonicalField(String v)  { this.canonicalField  = v; }
    public void setSuggestedBy(String v)     { this.suggestedBy     = v; }
    public void setSource(String v)          { this.source          = v; }
    public void setConfidence(Integer v)     { this.confidence      = v; }
}
