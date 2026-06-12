package com.ubs.pesubapi.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "lp_rates")
public class LpRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "lp_id", nullable = false)
    private Integer lpId;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(nullable = false)
    private String classification;

    @Column(name = "ubs_adv_rate_pct", nullable = false, precision = 7, scale = 4)
    private BigDecimal ubsAdvRatePct;

    @Column(name = "ubs_conc_limit_pct", nullable = false, precision = 7, scale = 4)
    private BigDecimal ubsConcLimitPct;

    @Column(nullable = false)
    private String source = "BATCH_FEED";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }

    // Getters
    public Integer    getId()              { return id; }
    public Integer    getLpId()            { return lpId; }
    public LocalDate  getEffectiveDate()   { return effectiveDate; }
    public String     getClassification()  { return classification; }
    public BigDecimal getUbsAdvRatePct()   { return ubsAdvRatePct; }
    public BigDecimal getUbsConcLimitPct() { return ubsConcLimitPct; }
    public String     getSource()          { return source; }
    public LocalDateTime getCreatedAt()    { return createdAt; }

    // Setters
    public void setLpId(Integer lpId)                       { this.lpId = lpId; }
    public void setEffectiveDate(LocalDate effectiveDate)   { this.effectiveDate = effectiveDate; }
    public void setClassification(String classification)    { this.classification = classification; }
    public void setUbsAdvRatePct(BigDecimal ubsAdvRatePct)  { this.ubsAdvRatePct = ubsAdvRatePct; }
    public void setUbsConcLimitPct(BigDecimal v)            { this.ubsConcLimitPct = v; }
    public void setSource(String source)                    { this.source = source; }
}
