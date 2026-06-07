package com.ubs.pesubapi.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "bb_templates")
public class BbTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "agent_bank", nullable = false, unique = true)
    private String agentBank;

    @Column(name = "sheet_name")
    private String sheetName;

    @Column(name = "header_row_index")
    private Integer headerRowIndex;

    @Column(name = "auto_learned", nullable = false)
    private boolean autoLearned = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    public Integer getId()             { return id; }
    public String  getAgentBank()      { return agentBank; }
    public String  getSheetName()      { return sheetName; }
    public Integer getHeaderRowIndex() { return headerRowIndex; }
    public boolean isAutoLearned()     { return autoLearned; }
    public LocalDateTime getCreatedAt(){ return createdAt; }
    public LocalDateTime getUpdatedAt(){ return updatedAt; }

    public void setAgentBank(String v)       { this.agentBank      = v; }
    public void setSheetName(String v)       { this.sheetName      = v; }
    public void setHeaderRowIndex(Integer v) { this.headerRowIndex = v; }
    public void setAutoLearned(boolean v)    { this.autoLearned    = v; }
}
