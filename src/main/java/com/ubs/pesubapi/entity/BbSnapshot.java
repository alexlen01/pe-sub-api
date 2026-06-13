package com.ubs.pesubapi.entity;

import com.ubs.pesubapi.dto.BbResult;
import com.ubs.pesubapi.entity.converter.BbResultConverter;
import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;
import java.time.LocalDateTime;

@Entity
@Table(name = "bb_snapshots")
public class BbSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "facility_id", nullable = false)
    private Integer facilityId;

    @Column(name = "calculated_by")
    private Integer calculatedBy;

    @Column(name = "calculated_at", nullable = false)
    private LocalDateTime calculatedAt;

    @Convert(converter = BbResultConverter.class)
    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private BbResult result;

    @PrePersist
    void onCreate() { calculatedAt = LocalDateTime.now(); }

    public Integer getId()              { return id; }
    public Integer getFacilityId()      { return facilityId; }
    public Integer getCalculatedBy()    { return calculatedBy; }
    public LocalDateTime getCalculatedAt() { return calculatedAt; }
    public BbResult getResult()         { return result; }

    public void setFacilityId(Integer facilityId) { this.facilityId = facilityId; }
    public void setResult(BbResult result)         { this.result = result; }
}
