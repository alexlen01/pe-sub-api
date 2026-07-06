package com.ubs.pesubapi.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;
import java.time.LocalDateTime;

@Entity
@Table(name = "config")
public class ConfigEntry {

    @Id
    @Column(length = 100)
    private String key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private JsonNode value;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void onSave() { updatedAt = LocalDateTime.now(); }

    public String getKey()           { return key; }
    public JsonNode getValue()       { return value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setKey(String key)       { this.key = key; }
    public void setValue(JsonNode value) { this.value = value; }
}
