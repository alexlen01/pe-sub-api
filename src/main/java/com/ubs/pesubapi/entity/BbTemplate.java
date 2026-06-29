package com.ubs.pesubapi.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "bb_templates")
public class BbTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "template_name", nullable = false)
    private String templateName;

    @Column(name = "template_class", nullable = false)
    private String templateClass = "A";

    @Column(name = "sheet_name")
    private String sheetName;

    @Column(name = "header_row_index")
    private Integer headerRowIndex;

    @Column(name = "auto_learned", nullable = false)
    private boolean autoLearned = true;

    @Column(name = "tranche_count", nullable = false)
    private int trancheCount = 1;

    @Column(name = "has_grouping_rows", nullable = false)
    private boolean hasGroupingRows = false;

    @Column(name = "has_color_flags", nullable = false)
    private boolean hasColorFlags = false;

    @Column(name = "summary_rows_above_header", nullable = false)
    private int summaryRowsAboveHeader = 0;

    @Column(name = "auto_discover_tabs", nullable = false)
    private boolean autoDiscoverTabs = false;

    // ── V1_14 registry extension: recognition + display fields ────────────────
    // Stable kebab-case id (e.g. "gs-blue-owl") used by recognition + UI.
    @Column(name = "template_slug", length = 50)
    private String templateSlug;

    // Agent bank name, distinct from the fund name in template_name.
    @Column(name = "agent_name")
    private String agentName;

    // Title anchor: 1-based Excel row + exact cell text confirming template identity.
    @Column(name = "title_row")
    private Integer titleRow;

    @Column(name = "title_text", columnDefinition = "text")
    private String titleText;

    // Summary block row range (e.g. "2-9") skipped before the header row.
    @Column(name = "summary_row_range", length = 20)
    private String summaryRowRange;

    // Keyword signals for filename / facility / fund recognition.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detect_keys", columnDefinition = "jsonb")
    private List<String> detectKeys = List.of();

    // Cell-format legend entries [{style, meaning}] for the registry detail panel.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "legend", columnDefinition = "jsonb")
    private List<Map<String, String>> legend = List.of();

    // Free-text analyst notes about extraction quirks for this template.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notes", columnDefinition = "jsonb")
    private List<String> notes = List.of();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    public Integer getId()                   { return id; }
    public String  getTemplateName()         { return templateName; }
    public String  getTemplateClass()        { return templateClass; }
    public String  getSheetName()            { return sheetName; }
    public Integer getHeaderRowIndex()       { return headerRowIndex; }
    public boolean isAutoLearned()           { return autoLearned; }
    public int     getTranchCount()          { return trancheCount; }
    public boolean isHasGroupingRows()       { return hasGroupingRows; }
    public boolean isHasColorFlags()         { return hasColorFlags; }
    public int     getSummaryRowsAboveHeader(){ return summaryRowsAboveHeader; }
    public boolean isAutoDiscoverTabs()      { return autoDiscoverTabs; }
    public String  getTemplateSlug()         { return templateSlug; }
    public String  getAgentName()            { return agentName; }
    public Integer getTitleRow()             { return titleRow; }
    public String  getTitleText()            { return titleText; }
    public String  getSummaryRowRange()      { return summaryRowRange; }
    public List<String> getDetectKeys()      { return detectKeys; }
    public List<Map<String, String>> getLegend() { return legend; }
    public List<String> getNotes()           { return notes; }
    public LocalDateTime getCreatedAt()      { return createdAt; }
    public LocalDateTime getUpdatedAt()      { return updatedAt; }

    public void setTemplateName(String v)           { this.templateName          = v; }
    public void setTemplateClass(String v)          { this.templateClass         = v; }
    public void setSheetName(String v)              { this.sheetName             = v; }
    public void setHeaderRowIndex(Integer v)        { this.headerRowIndex        = v; }
    public void setAutoLearned(boolean v)           { this.autoLearned           = v; }
    public void setTrancheCount(int v)              { this.trancheCount          = v; }
    public void setHasGroupingRows(boolean v)       { this.hasGroupingRows       = v; }
    public void setHasColorFlags(boolean v)         { this.hasColorFlags         = v; }
    public void setSummaryRowsAboveHeader(int v)    { this.summaryRowsAboveHeader= v; }
    public void setAutoDiscoverTabs(boolean v)      { this.autoDiscoverTabs     = v; }
    public void setTemplateSlug(String v)           { this.templateSlug         = v; }
    public void setAgentName(String v)              { this.agentName            = v; }
    public void setTitleRow(Integer v)              { this.titleRow             = v; }
    public void setTitleText(String v)              { this.titleText            = v; }
    public void setSummaryRowRange(String v)        { this.summaryRowRange      = v; }
    public void setDetectKeys(List<String> v)       { this.detectKeys           = v; }
    public void setLegend(List<Map<String, String>> v) { this.legend            = v; }
    public void setNotes(List<String> v)            { this.notes                = v; }
}
