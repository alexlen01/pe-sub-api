package com.ubs.pesubapi.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "report_history")
public class ReportHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 100)
    private String report;

    @Column(name = "facility_id")
    private Integer facilityId;

    @Column(name = "facility_name")
    private String facilityName;

    @Column(name = "snapshot_label", length = 100)
    private String snapshotLabel;

    @Column(length = 20)
    private String format;

    @Column(name = "user_name", length = 100)
    private String userName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }

    public Integer       getId()            { return id; }
    public String        getReport()        { return report; }
    public Integer       getFacilityId()    { return facilityId; }
    public String        getFacilityName()  { return facilityName; }
    public String        getSnapshotLabel() { return snapshotLabel; }
    public String        getFormat()        { return format; }
    public String        getUserName()      { return userName; }
    public LocalDateTime getCreatedAt()     { return createdAt; }

    public void setReport(String v)        { this.report        = v; }
    public void setFacilityId(Integer v)   { this.facilityId    = v; }
    public void setFacilityName(String v)  { this.facilityName  = v; }
    public void setSnapshotLabel(String v) { this.snapshotLabel = v; }
    public void setFormat(String v)        { this.format        = v; }
    public void setUserName(String v)      { this.userName      = v; }
}
