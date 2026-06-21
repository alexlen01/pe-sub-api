package com.ubs.pesubapi.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "facilities")
public class Facility {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "agent_bank", nullable = false)
    private String agentBank;

    @Column(nullable = false)
    private String status = "Not Started";

    @Column(name = "conc_limit_m", nullable = false, precision = 10, scale = 2)
    private BigDecimal concLimitM = BigDecimal.valueOf(25);

    @Column(name = "account_number")
    private String accountNumber;

    @Column(name = "loan_amount", precision = 15, scale = 2)
    private BigDecimal loanAmount;

    // Stated facility commitment and UBS's participation share (USD). Inputs to the Shadow BB
    // "Borrowing Base" summary (SHADOW_BB_ANALYSIS Table 2). Nullable until provided.
    @Column(name = "facility_size", precision = 15, scale = 2)
    private BigDecimal facilitySize;

    @Column(name = "ubs_participation", precision = 15, scale = 2)
    private BigDecimal ubsParticipation;

    @Column(name = "maturity_date")
    private LocalDate maturityDate;

    @Column(name = "bank_status")
    private String bankStatus;

    @Column(name = "bank_status_date")
    private LocalDate bankStatusDate;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Integer getId()                       { return id; }
    public String getName()                      { return name; }
    public String getAgentBank()                 { return agentBank; }
    public String getStatus()                    { return status; }
    public BigDecimal getConcLimitM()            { return concLimitM; }
    public String getAccountNumber()             { return accountNumber; }
    public BigDecimal getLoanAmount()            { return loanAmount; }
    public BigDecimal getFacilitySize()          { return facilitySize; }
    public BigDecimal getUbsParticipation()      { return ubsParticipation; }
    public LocalDate getMaturityDate()           { return maturityDate; }
    public String getBankStatus()                { return bankStatus; }
    public LocalDate getBankStatusDate()         { return bankStatusDate; }
    public LocalDateTime getLastRunAt()          { return lastRunAt; }
    public LocalDateTime getCreatedAt()          { return createdAt; }
    public LocalDateTime getUpdatedAt()          { return updatedAt; }

    public void setName(String name)             { this.name = name; }
    public void setAgentBank(String agentBank)   { this.agentBank = agentBank; }
    public void setStatus(String status)         { this.status = status; }
    public void setAccountNumber(String v)       { this.accountNumber = v; }
    public void setLoanAmount(BigDecimal v)      { this.loanAmount = v; }
    public void setFacilitySize(BigDecimal v)    { this.facilitySize = v; }
    public void setUbsParticipation(BigDecimal v){ this.ubsParticipation = v; }
    public void setMaturityDate(LocalDate v)     { this.maturityDate = v; }
    public void setBankStatus(String v)          { this.bankStatus = v; }
    public void setBankStatusDate(LocalDate v)   { this.bankStatusDate = v; }
    public void setLastRunAt(LocalDateTime t)    { this.lastRunAt = t; }
    public void setUpdatedAt(LocalDateTime t)    { this.updatedAt = t; }
}
