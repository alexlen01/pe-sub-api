package com.ubs.pesubapi.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Bank-wide canonical LP profile. One row per LP entity regardless of which
 * facilities the LP participates in.
 *
 * Stable identity / classification / financial scale fields are refreshed from
 * Agent BB extraction each time a Shadow BB cycle completes.  The UBS credit
 * profile fields (ubs_classification, ubs_default_adv_rate,
 * ubs_default_conc_limit) accumulate the credit officer's finalized decisions
 * and are pre-populated into new facility LP records at commit time, reducing
 * reclassification effort on subsequent submissions.
 */
@Entity
@Table(name = "lp_master")
public class LpMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "investor_name", nullable = false, unique = true)
    private String investorName;

    // ── Identity ──────────────────────────────────────────────────────────────

    private String parent;

    @Column(nullable = false)
    private boolean spv = false;

    @Column(name = "high_qty", nullable = false)
    private boolean highQty = true;

    @Column(name = "investor_type")
    private String investorType;

    @Column(name = "inst_vs_hnw")
    private String instVsHnw;

    @Column(name = "region_location")
    private String regionLocation;

    @Column(name = "investment_grade", nullable = false)
    private boolean ig = false;

    // ── Ratings ───────────────────────────────────────────────────────────────

    @Column(nullable = false)
    private String sp = "";

    @Column(nullable = false)
    private String mdy = "";

    @Column(nullable = false)
    private String fitch = "";

    // ── Financial scale ───────────────────────────────────────────────────────

    private String aum;
    private String nav;
    private String pension;

    @Column(name = "pension_funded")
    private String pensionFunded;

    // ── UBS credit profile — refined over Shadow BB cycles ───────────────────

    @Column(name = "ubs_classification")
    private String ubsClassification;

    @Column(name = "ubs_default_adv_rate")
    private String ubsDefaultAdvRate;

    @Column(name = "ubs_default_conc_limit")
    private String ubsDefaultConcLimit;

    // ── Metadata ──────────────────────────────────────────────────────────────

    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Integer getId()                 { return id; }
    public String  getInvestorName()       { return investorName; }
    public String  getParent()             { return parent; }
    public boolean isSpv()                 { return spv; }
    public boolean isHighQty()             { return highQty; }
    public String  getInvestorType()       { return investorType; }
    public String  getInstVsHnw()          { return instVsHnw; }
    public String  getInvType()            { return instVsHnw; }
    public String  getRegionLocation()     { return regionLocation; }
    public String  getRegion()             { return regionLocation; }
    public boolean isIg()                  { return ig; }
    public String  getSp()                 { return sp; }
    public String  getMdy()                { return mdy; }
    public String  getFitch()              { return fitch; }
    public String  getAum()                { return aum; }
    public String  getNav()                { return nav; }
    public String  getPension()            { return pension; }
    public String  getPensionFunded()      { return pensionFunded; }
    public String  getUbsClassification()  { return ubsClassification; }
    public String  getUbsDefaultAdvRate()  { return ubsDefaultAdvRate; }
    public String  getUbsDefaultConcLimit(){ return ubsDefaultConcLimit; }
    public String  getNotes()              { return notes; }
    public LocalDateTime getCreatedAt()    { return createdAt; }
    public LocalDateTime getUpdatedAt()    { return updatedAt; }

    // ── Setters ───────────────────────────────────────────────────────────────

    public void setInvestorName(String v)        { this.investorName = v; }
    public void setParent(String v)              { this.parent = v; }
    public void setSpv(boolean v)                { this.spv = v; }
    public void setHighQty(boolean v)            { this.highQty = v; }
    public void setInvestorType(String v)        { this.investorType = v; }
    public void setInstVsHnw(String v)           { this.instVsHnw = v; }
    public void setInvType(String v) {
        if ("Institutional".equals(v) || "HNW".equals(v)) {
            setInstVsHnw(v);
        } else {
            this.investorType = v;
        }
    }
    public void setRegionLocation(String v)      { this.regionLocation = v; }
    public void setRegion(String v)              { this.regionLocation = v; }
    public void setIg(boolean v)                 { this.ig = v; }
    public void setSp(String v)                  { this.sp = v != null ? v : ""; }
    public void setMdy(String v)                 { this.mdy = v != null ? v : ""; }
    public void setFitch(String v)               { this.fitch = v != null ? v : ""; }
    public void setAum(String v)                 { this.aum = v; }
    public void setNav(String v)                 { this.nav = v; }
    public void setPension(String v)             { this.pension = v; }
    public void setPensionFunded(String v)       { this.pensionFunded = v; }
    public void setUbsClassification(String v)   { this.ubsClassification = v; }
    public void setUbsDefaultAdvRate(String v)   { this.ubsDefaultAdvRate = v; }
    public void setUbsDefaultConcLimit(String v) { this.ubsDefaultConcLimit = v; }
    public void setNotes(String v)               { this.notes = v; }
    public void setUpdatedAt(LocalDateTime v)    { this.updatedAt = v; }
}
