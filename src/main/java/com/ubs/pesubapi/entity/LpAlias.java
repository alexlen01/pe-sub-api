package com.ubs.pesubapi.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * An uploaded Agent BB investor string an analyst has accepted against an {@link LpMaster} record.
 *
 * <p>The matching feedback loop: once the alias exists, the next upload carrying that exact string
 * resolves in O(1) at score 100 without fuzzy scoring, while still running the same parent/child
 * routing as a fuzzy hit. Aliases are stored against the record that was actually matched — the
 * child/feeder, not its parent — so the audit trail keeps pointing at the entity the agent named.
 */
@Entity
@Table(name = "lp_aliases")
public class LpAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "lp_master_id", nullable = false)
    private Integer lpMasterId;

    /** Normalised-for-lookup uploaded name. Unique across LP Master — one string, one owner. */
    @Column(name = "uploaded_name", nullable = false, unique = true)
    private String uploadedName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }

    protected LpAlias() { }

    public LpAlias(Integer lpMasterId, String uploadedName) {
        this.lpMasterId   = lpMasterId;
        this.uploadedName = uploadedName;
    }

    public Integer       getId()           { return id; }
    public Integer       getLpMasterId()   { return lpMasterId; }
    public String        getUploadedName() { return uploadedName; }
    public LocalDateTime getCreatedAt()    { return createdAt; }

    public void setLpMasterId(Integer v)   { this.lpMasterId = v; }
    public void setUploadedName(String v)  { this.uploadedName = v; }
}
