package com.ubs.pesubapi.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "lp_records")
public class Lp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "facility_id", nullable = false)
    private Integer facilityId;

    @Column(name = "investor_name", nullable = false)
    private String investorName;

    private String parent;

    @Column(nullable = false)
    private boolean spv = false;

    @Column(name = "high_qty", nullable = false)
    private boolean highQty = true;

    @Column(name = "inv_type", nullable = false)
    private String invType;

    @Column(nullable = false)
    private String region;

    @Column(nullable = false)
    private boolean ig = false;

    @Column(nullable = false)
    private String cls;

    @Column(name = "cls_tag")
    private String clsTag;

    @Column(nullable = false)
    private String sp = "";

    @Column(nullable = false)
    private String mdy = "";

    @Column(nullable = false)
    private String fitch = "";

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

    @Column(name = "recallable_dist")
    private String recallableDist;

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
    public Integer getId()              { return id; }
    public Integer getFacilityId()      { return facilityId; }
    public String getInvestorName()     { return investorName; }
    public String getParent()           { return parent; }
    public boolean isSpv()              { return spv; }
    public boolean isHighQty()          { return highQty; }
    public String getInvType()          { return invType; }
    public String getRegion()           { return region; }
    public boolean isIg()               { return ig; }
    public String getCls()              { return cls; }
    public String getClsTag()           { return clsTag; }
    public String getSp()               { return sp; }
    public String getMdy()              { return mdy; }
    public String getFitch()            { return fitch; }
    public String getAum()              { return aum; }
    public String getNav()              { return nav; }
    public String getPension()          { return pension; }
    public String getPensionFunded()    { return pensionFunded; }
    public String getCapCommit()        { return capCommit; }
    public String getPctCapCommit()     { return pctCapCommit; }
    public String getCalledCap()        { return calledCap; }
    public String getUc()               { return uc; }
    public String getPctUncalled()      { return pctUncalled; }
    public String getPctCalled()        { return pctCalled; }
    public String getAgentConc()        { return agentConc; }
    public String getUbsConc()          { return ubsConc; }
    public String getAgentRate()        { return agentRate; }
    public String getAbb()              { return abb; }
    public boolean isInc()              { return inc; }
    public boolean isRcl()              { return rcl; }
    public String getRecallableDist()   { return recallableDist; }
    public boolean isTf()               { return tf; }
    public String getNotes()            { return notes; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    // Setters for creation
    public void setFacilityId(Integer v)     { this.facilityId = v; }
    public void setInvestorName(String v)    { this.investorName = v; }
    public void setInvType(String v)         { this.invType = v; }
    public void setRegion(String v)          { this.region = v; }
    public void setParent(String v)          { this.parent = v; }
    public void setSpv(boolean v)            { this.spv = v; }
    public void setHighQty(boolean v)        { this.highQty = v; }
    public void setIg(boolean v)             { this.ig = v; }
    public void setSp(String v)              { this.sp = v; }
    public void setMdy(String v)             { this.mdy = v; }
    public void setFitch(String v)           { this.fitch = v; }
    public void setTf(boolean v)             { this.tf = v; }

    // Setters for patch
    public void setCls(String cls)           { this.cls = cls; }
    public void setClsTag(String clsTag)     { this.clsTag = clsTag; }
    public void setNotes(String notes)       { this.notes = notes; }
    public void setAbb(String abb)           { this.abb = abb; }
    public void setInc(boolean inc)          { this.inc = inc; }
    public void setRcl(boolean rcl)                    { this.rcl = rcl; }
    public void setRecallableDist(String v)            { this.recallableDist = v; }
    public void setUpdatedAt(LocalDateTime t)          { this.updatedAt = t; }

    // Setters for extraction ingest
    public void setAum(String aum)               { this.aum = aum; }
    public void setCapCommit(String capCommit)   { this.capCommit = capCommit; }
    public void setUc(String uc)                 { this.uc = uc; }
    public void setAgentRate(String agentRate)   { this.agentRate = agentRate; }
    public void setAgentConc(String agentConc)   { this.agentConc = agentConc; }

    // Setters for Shadow BB commit (full LP Master population)
    public void setNav(String nav)                     { this.nav = nav; }
    public void setPension(String pension)             { this.pension = pension; }
    public void setPensionFunded(String v)             { this.pensionFunded = v; }
    public void setPctCapCommit(String v)              { this.pctCapCommit = v; }
    public void setCalledCap(String calledCap)         { this.calledCap = calledCap; }
    public void setPctUncalled(String v)               { this.pctUncalled = v; }
    public void setPctCalled(String v)                 { this.pctCalled = v; }
    public void setUbsConc(String ubsConc)             { this.ubsConc = ubsConc; }
}
