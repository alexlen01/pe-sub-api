package com.ubs.pesubapi.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String event;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "facility_id")
    private Integer facilityId;

    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "user_name", length = 100)
    private String userName;

    @Column(name = "user_display", length = 255)
    private String userDisplay;

    @Column(length = 45)
    private String ip;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }

    public Integer       getId()         { return id; }
    public String        getEvent()      { return event; }
    public String        getDetail()     { return detail; }
    public Integer       getFacilityId() { return facilityId; }
    public Integer       getUserId()     { return userId; }
    public String        getUserName()   { return userName; }
    public String        getUserDisplay(){ return userDisplay; }
    public String        getIp()         { return ip; }
    public LocalDateTime getCreatedAt()  { return createdAt; }

    public void setEvent(String v)       { this.event      = v; }
    public void setDetail(String v)      { this.detail     = v; }
    public void setFacilityId(Integer v) { this.facilityId = v; }
    public void setUserId(Integer v)     { this.userId     = v; }
    public void setUserName(String v)    { this.userName   = v; }
    public void setUserDisplay(String v) { this.userDisplay = v; }
    public void setIp(String v)          { this.ip         = v; }
}
