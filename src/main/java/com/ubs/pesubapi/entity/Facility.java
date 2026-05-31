package com.ubs.pesubapi.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
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
    public LocalDateTime getLastRunAt()          { return lastRunAt; }
    public LocalDateTime getCreatedAt()          { return createdAt; }
    public LocalDateTime getUpdatedAt()          { return updatedAt; }

    public void setName(String name)             { this.name = name; }
    public void setAgentBank(String agentBank)   { this.agentBank = agentBank; }
    public void setStatus(String status)         { this.status = status; }
    public void setLastRunAt(LocalDateTime t)    { this.lastRunAt = t; }
    public void setUpdatedAt(LocalDateTime t)    { this.updatedAt = t; }
}
