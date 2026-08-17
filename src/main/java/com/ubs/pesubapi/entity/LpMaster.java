package com.ubs.pesubapi.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Bank-wide canonical LP profile. One row per LP entity regardless of which
 * facilities the LP participates in.
 *
 * <p>Stable identity, LP category and financial-scale fields are refreshed from Agent BB extraction
 * each time a Shadow BB cycle completes. The UBS credit-profile fields ({@code ubsLpCategory},
 * {@code ubsDefaultAdvanceRate}, {@code ubsDefaultConcentrationLimit}) accumulate the credit
 * officer's finalized decisions and are pre-populated into new facility LP records at commit time,
 * reducing reclassification effort on subsequent submissions.
 *
 * <p>Field names mirror {@link LpRecord} exactly, so the copy between the two is a plain assignment
 * per field. LP Category is the bank's BB risk bucket — not the LP's regulatory classification, and
 * not {@code investorType}.
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

    /**
     * Parent/sponsor name as displayed and as delivered by ingest. Kept alongside {@link #parentId}
     * because pe-sub-jobs, Agent BB extraction rows and {@link LpRecord} all speak the name; the id
     * is the resolved link. {@code LpMasterService} keeps the pair consistent on write.
     */
    private String parent;

    /** Resolved self-reference to the parent/sponsor row. {@code null} on an ultimate entity. */
    @Column(name = "parent_id")
    private Integer parentId;

    /** Mirrors {@code parentId == null}; persisted so the ultimate-entity filter is indexable. */
    @Column(name = "is_ultimate_parent", nullable = false)
    private boolean ultimateParent = true;

    @Column(nullable = false)
    private boolean spv = false;

    @Column(name = "high_quality", nullable = false)
    private boolean highQuality = true;

    @Column(name = "investor_type")
    private String investorType;

    @Column(name = "institutional_or_hnw")
    private String institutionalOrHnw;

    @Column(name = "region_location")
    private String regionLocation;

    @Column(name = "investment_grade", nullable = false)
    private boolean investmentGrade = false;

    // ── Ratings ───────────────────────────────────────────────────────────────

    @Column(name = "sp_rating", nullable = false)
    private String spRating = "";

    @Column(name = "moodys_rating", nullable = false)
    private String moodysRating = "";

    @Column(name = "fitch_rating", nullable = false)
    private String fitchRating = "";

    // ── Financial scale ───────────────────────────────────────────────────────

    private String aum;
    private String nav;

    @Column(name = "pension_assets")
    private String pensionAssets;

    // Pension funded status as a fraction: 0.9100 is 91%.
    @Column(name = "funding_ratio")
    private BigDecimal fundingRatio;

    // ── UBS credit profile — refined over Shadow BB cycles ───────────────────

    @Column(name = "ubs_lp_category")
    private String ubsLpCategory;

    // Fraction, never percent-scaled: 0.9000 is 90%.
    @Column(name = "ubs_default_advance_rate")
    private BigDecimal ubsDefaultAdvanceRate;

    // Mirrors LpRecord.ubsConcentrationLimit, percent-or-dollars magnitude split included —
    // not a fraction.
    @Column(name = "ubs_default_concentration_limit")
    private BigDecimal ubsDefaultConcentrationLimit;

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

    public Integer getId()                    { return id; }
    public String  getInvestorName()          { return investorName; }
    public String  getParent()                { return parent; }
    public Integer getParentId()              { return parentId; }
    public boolean isUltimateParent()         { return ultimateParent; }
    public boolean isSpv()                    { return spv; }
    public boolean isHighQuality()            { return highQuality; }
    public String  getInvestorType()          { return investorType; }
    public String  getInstitutionalOrHnw()    { return institutionalOrHnw; }
    public String  getRegionLocation()        { return regionLocation; }
    public boolean isInvestmentGrade()        { return investmentGrade; }
    public String  getSpRating()              { return spRating; }
    public String  getMoodysRating()          { return moodysRating; }
    public String  getFitchRating()           { return fitchRating; }
    public String  getAum()                   { return aum; }
    public String  getNav()                   { return nav; }
    public String  getPensionAssets()         { return pensionAssets; }
    public BigDecimal getFundingRatio()       { return fundingRatio; }
    public String  getUbsLpCategory()         { return ubsLpCategory; }
    public BigDecimal getUbsDefaultAdvanceRate()       { return ubsDefaultAdvanceRate; }
    public BigDecimal getUbsDefaultConcentrationLimit(){ return ubsDefaultConcentrationLimit; }
    public String  getNotes()                 { return notes; }
    public LocalDateTime getCreatedAt()       { return createdAt; }
    public LocalDateTime getUpdatedAt()       { return updatedAt; }

    // ── Setters ───────────────────────────────────────────────────────────────

    public void setInvestorName(String v)        { this.investorName = v; }
    public void setParent(String v)              { this.parent = v; }

    /** Sets the resolved parent link and keeps the derived ultimate-entity flag in step. */
    public void setParentId(Integer v)           { this.parentId = v; this.ultimateParent = (v == null); }
    public void setSpv(boolean v)                { this.spv = v; }
    public void setHighQuality(boolean v)        { this.highQuality = v; }
    public void setInvestorType(String v)        { this.investorType = v; }
    public void setInstitutionalOrHnw(String v)  { this.institutionalOrHnw = v; }

    /**
     * Accepts either the Institutional/HNW segment or an industry investor type on the one inbound
     * field older payloads use for both, and routes it to the right column.
     */
    public void setInvestorSegmentOrType(String v) {
        if ("Institutional".equals(v) || "HNW".equals(v)) {
            setInstitutionalOrHnw(v);
        } else {
            this.investorType = v;
        }
    }

    public void setRegionLocation(String v)      { this.regionLocation = v; }
    public void setInvestmentGrade(boolean v)    { this.investmentGrade = v; }
    public void setSpRating(String v)            { this.spRating = v != null ? v : ""; }
    public void setMoodysRating(String v)        { this.moodysRating = v != null ? v : ""; }
    public void setFitchRating(String v)         { this.fitchRating = v != null ? v : ""; }
    public void setAum(String v)                 { this.aum = v; }
    public void setNav(String v)                 { this.nav = v; }
    public void setPensionAssets(String v)       { this.pensionAssets = v; }
    public void setFundingRatio(BigDecimal v)    { this.fundingRatio = v; }
    public void setUbsLpCategory(String v)       { this.ubsLpCategory = v; }
    public void setUbsDefaultAdvanceRate(BigDecimal v)        { this.ubsDefaultAdvanceRate = v; }
    public void setUbsDefaultConcentrationLimit(BigDecimal v) { this.ubsDefaultConcentrationLimit = v; }
    public void setNotes(String v)               { this.notes = v; }
    public void setUpdatedAt(LocalDateTime v)    { this.updatedAt = v; }
}
