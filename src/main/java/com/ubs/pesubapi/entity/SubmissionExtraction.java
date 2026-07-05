package com.ubs.pesubapi.entity;

import tools.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "submission_extractions")
public class SubmissionExtraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "submission_id", nullable = false)
    private Integer submissionId;

    @Column(name = "template_format")
    private String templateFormat;

    @Column(name = "template_version")
    private String templateVersion;

    @Column(name = "sheet_name")
    private String sheetName;

    @Column(name = "header_row_index")
    private Integer headerRowIndex;

    @Column(name = "forced_template")
    private String forcedTemplate;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "flagged_count", nullable = false)
    private int flaggedCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extracted_lps", columnDefinition = "jsonb")
    private JsonNode extractedLps;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "field_mappings", columnDefinition = "jsonb")
    private JsonNode fieldMappings;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "unrecognized_columns", columnDefinition = "jsonb")
    private JsonNode unrecognizedColumns;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }

    public Integer getId()                    { return id; }
    public Integer getSubmissionId()          { return submissionId; }
    public String getTemplateFormat()         { return templateFormat; }
    public String getTemplateVersion()        { return templateVersion; }
    public String getSheetName()              { return sheetName; }
    public Integer getHeaderRowIndex()        { return headerRowIndex; }
    public String getForcedTemplate()         { return forcedTemplate; }
    public int getTotalRows()                 { return totalRows; }
    public int getFlaggedCount()              { return flaggedCount; }
    public JsonNode getExtractedLps()         { return extractedLps; }
    public JsonNode getFieldMappings()        { return fieldMappings; }
    public JsonNode getUnrecognizedColumns()  { return unrecognizedColumns; }
    public LocalDateTime getCreatedAt()       { return createdAt; }

    public void setSubmissionId(Integer v)          { this.submissionId = v; }
    public void setTemplateFormat(String v)         { this.templateFormat = v; }
    public void setTemplateVersion(String v)        { this.templateVersion = v; }
    public void setSheetName(String v)              { this.sheetName = v; }
    public void setHeaderRowIndex(Integer v)        { this.headerRowIndex = v; }
    public void setForcedTemplate(String v)         { this.forcedTemplate = v; }
    public void setTotalRows(int v)                 { this.totalRows = v; }
    public void setFlaggedCount(int v)              { this.flaggedCount = v; }
    public void setExtractedLps(JsonNode v)         { this.extractedLps = v; }
    public void setFieldMappings(JsonNode v)        { this.fieldMappings = v; }
    public void setUnrecognizedColumns(JsonNode v)  { this.unrecognizedColumns = v; }
}
