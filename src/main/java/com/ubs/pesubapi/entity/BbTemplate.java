package com.ubs.pesubapi.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

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
}
