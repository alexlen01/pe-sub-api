package com.ubs.pesubapi.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "lps")
public class Lp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "facility_id", nullable = false)
    private Integer facilityId;

    @Column(nullable = false)
    private Integer rank;

    @Column(nullable = false)
    private String name;

    private String parent;

    @Column(nullable = false)
    private boolean spv = false;

    @Column(nullable = false)
    private boolean hq = true;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String region;

    @Column(nullable = false)
    private boolean ig = false;

    @Column(nullable = false)
    private String cls;

    @Column(name = "cls_tag")
    private String clsTag;

    @Column(nullable = false)
    private String sp = "NR";

    @Column(nullable = false)
    private String mdy = "NR";

    @Column(nullable = false)
    private String fitch = "NR";

    private String aum;
    private String nav;
    private String pension;

    @Column(name = "pension_funded")
    private String pensionFunded;

    @Column(name = "cap_commit")
    private String capCommit;

    @Column(name = "pct_cap_commit")
    private String pctCapCommit;

    @Column(name = "called_cap")
    private String calledCap;

    private String uc;

    @Column(name = "pct_uncalled")
    private String pctUncalled;

    @Column(name = "pct_called")
    private String pctCalled;

    @Column(name = "agent_conc")
    private String agentConc;

    @Column(name = "ubs_conc")
    private String ubsConc;

    @Column(name = "agent_rate")
    private String agentRate;

    private String abb;

    @Column(nullable = false)
    private boolean inc = true;

    @Column(nullable = false)
    private boolean rcl = false;

    @Column(nullable = false)
    private boolean tf = false;

    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    // Getters
    public Integer getId()         { return id; }
    public Integer getFacilityId() { return facilityId; }
    public Integer getRank()       { return rank; }
    public String getName()        { return name; }
    public String getParent()      { return parent; }
    public boolean isSpv()         { return spv; }
    public boolean isHq()          { return hq; }
    public String getType()        { return type; }
    public String getRegion()      { return region; }
    public boolean isIg()          { return ig; }
    public String getCls()         { return cls; }
    public String getClsTag()      { return clsTag; }
    public String getSp()          { return sp; }
    public String getMdy()         { return mdy; }
    public String getFitch()       { return fitch; }
    public String getAum()         { return aum; }
    public String getNav()         { return nav; }
    public String getPension()     { return pension; }
    public String getPensionFunded(){ return pensionFunded; }
    public String getCapCommit()   { return capCommit; }
    public String getPctCapCommit(){ return pctCapCommit; }
    public String getCalledCap()   { return calledCap; }
    public String getUc()          { return uc; }
    public String getPctUncalled() { return pctUncalled; }
    public String getPctCalled()   { return pctCalled; }
    public String getAgentConc()   { return agentConc; }
    public String getUbsConc()     { return ubsConc; }
    public String getAgentRate()   { return agentRate; }
    public String getAbb()         { return abb; }
    public boolean isInc()         { return inc; }
    public boolean isRcl()         { return rcl; }
    public boolean isTf()          { return tf; }
    public String getNotes()       { return notes; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    // Setters for patch
    public void setCls(String cls)         { this.cls = cls; }
    public void setClsTag(String clsTag)   { this.clsTag = clsTag; }
    public void setNotes(String notes)     { this.notes = notes; }
    public void setAbb(String abb)         { this.abb = abb; }
    public void setInc(boolean inc)        { this.inc = inc; }
    public void setRcl(boolean rcl)        { this.rcl = rcl; }
    public void setUpdatedAt(LocalDateTime t) { this.updatedAt = t; }
}
