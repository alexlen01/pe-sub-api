package com.ubs.pesubapi.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One LP's participation in one facility — the per-facility working copy of the bank-wide
 * {@link LpMaster} profile, carrying the eligibility, rate and borrowing-base figures for that
 * facility's Shadow BB.
 *
 * <p>Field names are spelled out in full and match their columns one-for-one. Three distinctions
 * that the old abbreviations blurred are load-bearing:
 * <ul>
 *   <li>{@code highQuality} is a BB quality-tier flag, not a quantity.</li>
 *   <li>{@code ubsLpCategory} / {@code agentLpCategory} are the bank's and the agent's BB risk
 *       buckets. Neither is the LP's regulatory <em>classification</em> (QP/QIB/ERISA), and neither
 *       is {@code investorType} (industry profile).</li>
 *   <li>{@code ubsAdvanceRate} is UBS's own rate and overrides the criteria-matrix default;
 *       {@code agentAdvanceRate} is the agent bank's, extracted verbatim from their workbook.</li>
 * </ul>
 *
 * <p>Percents and advance rates are fractions (0.9100 = 91%). The two concentration limits are the
 * exception — they hold either a percent of total uncalled (7.5) or an absolute dollar cap.
 */
@Entity
@Table(name = "lp_records")
public class LpRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "facility_id", nullable = false)
    private Integer facilityId;

    // Position of this LpRecord in its originating Agent BB (the extraction row index). Drives the
    // natural, source-file ordering of the LP Master. Null for manually-created/legacy records.
    @Column(name = "source_seq")
    private Integer sourceSeq;

    @Column(name = "investor_name", nullable = false)
    private String investorName;

    private String parent;

    @Column(nullable = false)
    private boolean spv = false;

    @Column(name = "high_quality", nullable = false)
    private boolean highQuality = true;

    @Column(name = "investor_type", nullable = false)
    private String investorType = "";

    @Column(name = "institutional_or_hnw", nullable = false)
    private String institutionalOrHnw = "Institutional";

    @Column(name = "region_location", nullable = false)
    private String regionLocation;

    @Column(name = "investment_grade", nullable = false)
    private boolean investmentGrade = false;

    // Agent LP Category (verbatim from Agent BB) — precedes the UBS LP Category model-wide.
    @Column(name = "agent_lp_category")
    private String agentLpCategory;

    @Column(name = "agent_lp_category_source")
    private String agentLpCategorySource;

    @Column(name = "ubs_lp_category", nullable = false)
    private String ubsLpCategory;

    @Column(name = "ubs_lp_category_tag")
    private String ubsLpCategoryTag;

    // ── Shadow BB 28-column alignment (Shadow_BB.xlsx) ──

    // UBS Advance Rate (manual input). Fraction, never percent-scaled: 0.9000 is 90%.
    @Column(name = "ubs_advance_rate")
    private BigDecimal ubsAdvanceRate;

    @Column(name = "agent_excess_concentration")
    private BigDecimal agentExcessConcentration;   // Agent Excess Concentration Base (calculated)

    @Column(name = "ubs_excess_concentration")
    private BigDecimal ubsExcessConcentration;     // UBS Excess Concentration Base (calculated)

    @Column(name = "ubs_borrowing_base")
    private BigDecimal ubsBorrowingBase;

    @Column(name = "sp_rating", nullable = false)
    private String spRating = "";

    @Column(name = "moodys_rating", nullable = false)
    private String moodysRating = "";

    @Column(name = "fitch_rating", nullable = false)
    private String fitchRating = "";

    // LP-size display fields — text on both lp_records and lp_master, never BB inputs.
    @Column(name = "aum")
    private String aum;

    private String nav;

    @Column(name = "pension_assets")
    private String pensionAssets;

    // Pension funded status as a fraction: 0.9100 is 91%.
    @Column(name = "funding_ratio")
    private BigDecimal fundingRatio;

    @Column(name = "capital_commitment")
    private BigDecimal capitalCommitment;

    // The LP's commitment as a fraction of the fund's total commitments.
    @Column(name = "pct_of_fund_commitments")
    private BigDecimal pctOfFundCommitments;

    @Column(name = "called_capital")
    private BigDecimal calledCapital;

    @Column(name = "uncalled_capital")
    private BigDecimal uncalledCapital;

    // The LP's uncalled capital as a fraction of the fund's total uncalled.
    @Column(name = "pct_of_fund_uncalled")
    private BigDecimal pctOfFundUncalled;

    // The fraction of this LP's own commitment that has been drawn.
    @Column(name = "pct_lp_called")
    private BigDecimal pctLpCalled;

    @Column(name = "agent_concentration_limit")
    private BigDecimal agentConcentrationLimit;

    @Column(name = "ubs_concentration_limit")
    private BigDecimal ubsConcentrationLimit;

    // Agent Advance Rate. Fraction, never percent-scaled: 0.9500 is 95%.
    @Column(name = "agent_advance_rate")
    private BigDecimal agentAdvanceRate;

    @Column(name = "agent_borrowing_base")
    private BigDecimal agentBorrowingBase;

    @Column(name = "included", nullable = false)
    private boolean included = true;

    @Column(name = "reclassified", nullable = false)
    private boolean reclassified = false;

    @Column(name = "recallable_distributions")
    private BigDecimal recallableDistributions;

    @Column(name = "transferee", nullable = false)
    private boolean transferee = false;

    @Column(name = "lp_rank")
    private Integer lpRank;

    private String notes;

    @Column(name = "lp_master_id")
    private Integer lpMasterId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Integer getId()                       { return id; }
    public Integer getFacilityId()               { return facilityId; }
    public Integer getSourceSeq()                { return sourceSeq; }
    public String getInvestorName()              { return investorName; }
    public String getParent()                    { return parent; }
    public boolean isSpv()                       { return spv; }
    public boolean isHighQuality()               { return highQuality; }
    public String getInvestorType()              { return investorType; }
    public String getInstitutionalOrHnw()        { return institutionalOrHnw; }
    public String getRegionLocation()            { return regionLocation; }
    public boolean isInvestmentGrade()           { return investmentGrade; }
    public String getUbsLpCategory()             { return ubsLpCategory; }
    public String getUbsLpCategoryTag()          { return ubsLpCategoryTag; }
    public String getAgentLpCategory()           { return agentLpCategory; }
    public String getAgentLpCategorySource()     { return agentLpCategorySource; }
    public BigDecimal getUbsAdvanceRate()        { return ubsAdvanceRate; }
    public BigDecimal getAgentExcessConcentration() { return agentExcessConcentration; }
    public BigDecimal getUbsExcessConcentration()   { return ubsExcessConcentration; }
    public BigDecimal getUbsBorrowingBase()        { return ubsBorrowingBase; }
    public String getSpRating()                  { return spRating; }
    public String getMoodysRating()              { return moodysRating; }
    public String getFitchRating()               { return fitchRating; }
    public String getAum()                       { return aum; }
    public String getNav()                       { return nav; }
    public String getPensionAssets()             { return pensionAssets; }
    public BigDecimal getFundingRatio()          { return fundingRatio; }
    public BigDecimal getCapitalCommitment()     { return capitalCommitment; }
    public BigDecimal getPctOfFundCommitments()  { return pctOfFundCommitments; }
    public BigDecimal getCalledCapital()         { return calledCapital; }
    public BigDecimal getUncalledCapital()       { return uncalledCapital; }
    public BigDecimal getPctOfFundUncalled()     { return pctOfFundUncalled; }
    public BigDecimal getPctLpCalled()           { return pctLpCalled; }
    public BigDecimal getAgentConcentrationLimit() { return agentConcentrationLimit; }
    public BigDecimal getUbsConcentrationLimit() { return ubsConcentrationLimit; }
    public BigDecimal getAgentAdvanceRate()      { return agentAdvanceRate; }
    public BigDecimal getAgentBorrowingBase()    { return agentBorrowingBase; }
    public boolean isIncluded()                  { return included; }
    public boolean isReclassified()              { return reclassified; }
    public BigDecimal getRecallableDistributions() { return recallableDistributions; }
    public boolean isTransferee()                { return transferee; }
    public Integer getLpRank()                   { return lpRank; }
    public String getNotes()                     { return notes; }
    public Integer getLpMasterId()               { return lpMasterId; }
    public LocalDateTime getCreatedAt()          { return createdAt; }
    public LocalDateTime getUpdatedAt()          { return updatedAt; }

    // ── Setters ───────────────────────────────────────────────────────────────

    public void setFacilityId(Integer v)         { this.facilityId = v; }
    public void setLpMasterId(Integer v)         { this.lpMasterId = v; }
    public void setSourceSeq(Integer v)          { this.sourceSeq = v; }
    public void setInvestorName(String v)        { this.investorName = v; }
    public void setInvestorType(String v)        { this.investorType = v != null ? v : ""; }
    public void setInstitutionalOrHnw(String v)  { this.institutionalOrHnw = v != null && !v.isBlank() ? v : "Institutional"; }

    /**
     * Accepts either the Institutional/HNW segment or an industry investor type on the one inbound
     * field older payloads use for both, and routes it to the right column.
     */
    public void setInvestorSegmentOrType(String v) {
        if ("Institutional".equals(v) || "HNW".equals(v)) {
            setInstitutionalOrHnw(v);
            if (investorType == null) investorType = "";
        } else {
            setInvestorType(v);
        }
    }

    public void setRegionLocation(String v)      { this.regionLocation = v; }
    public void setParent(String v)              { this.parent = v; }
    public void setSpv(boolean v)                { this.spv = v; }
    public void setHighQuality(boolean v)        { this.highQuality = v; }
    public void setInvestmentGrade(boolean v)    { this.investmentGrade = v; }
    public void setSpRating(String v)            { this.spRating = v; }
    public void setMoodysRating(String v)        { this.moodysRating = v; }
    public void setFitchRating(String v)         { this.fitchRating = v; }
    public void setTransferee(boolean v)         { this.transferee = v; }

    public void setUbsLpCategory(String v)       { this.ubsLpCategory = v; }
    public void setUbsLpCategoryTag(String v)    { this.ubsLpCategoryTag = v; }
    public void setAgentLpCategory(String v)     { this.agentLpCategory = v; }
    public void setAgentLpCategorySource(String v) { this.agentLpCategorySource = v; }

    public void setNotes(String v)               { this.notes = v; }
    public void setIncluded(boolean v)           { this.included = v; }
    public void setReclassified(boolean v)       { this.reclassified = v; }
    public void setRecallableDistributions(BigDecimal v) { this.recallableDistributions = v; }
    public void setUpdatedAt(LocalDateTime v)    { this.updatedAt = v; }
    public void setLpRank(Integer v)             { this.lpRank = v; }

    public void setAum(String v)                 { this.aum = v; }
    public void setNav(String v)                 { this.nav = v; }
    public void setPensionAssets(String v)       { this.pensionAssets = v; }
    public void setFundingRatio(BigDecimal v)    { this.fundingRatio = v; }

    public void setCapitalCommitment(BigDecimal v)    { this.capitalCommitment = v; }
    public void setPctOfFundCommitments(BigDecimal v) { this.pctOfFundCommitments = v; }
    public void setCalledCapital(BigDecimal v)        { this.calledCapital = v; }
    public void setUncalledCapital(BigDecimal v)      { this.uncalledCapital = v; }
    public void setPctOfFundUncalled(BigDecimal v)    { this.pctOfFundUncalled = v; }
    public void setPctLpCalled(BigDecimal v)          { this.pctLpCalled = v; }

    public void setAgentConcentrationLimit(BigDecimal v) { this.agentConcentrationLimit = v; }
    public void setUbsConcentrationLimit(BigDecimal v)   { this.ubsConcentrationLimit = v; }
    public void setAgentAdvanceRate(BigDecimal v)        { this.agentAdvanceRate = v; }
    public void setUbsAdvanceRate(BigDecimal v)          { this.ubsAdvanceRate = v; }
    public void setAgentBorrowingBase(BigDecimal v)      { this.agentBorrowingBase = v; }
    public void setUbsBorrowingBase(BigDecimal v)        { this.ubsBorrowingBase = v; }
    public void setAgentExcessConcentration(BigDecimal v) { this.agentExcessConcentration = v; }
    public void setUbsExcessConcentration(BigDecimal v)   { this.ubsExcessConcentration = v; }
}
