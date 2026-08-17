package com.ubs.pesubapi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A person who has authenticated through the gateway, mirrored from the {@code X-Auth-*} headers.
 * Never a credential record — there is no password, and the application does not authenticate
 * anyone itself. Rows exist so screens can resolve a stored uuName to a display name.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /**
     * Stable authentication identity (e.g. {@code le05751}) — the natural key. A real uuName is 7
     * alphanumeric characters; the extra width is headroom for system/override identities.
     */
    @Column(name = "uu_name", nullable = false, unique = true, length = 50)
    private String uuName;

    @Column(name = "first_name", nullable = false)
    private String firstName = "";

    @Column(name = "last_name", nullable = false)
    private String lastName = "";

    @Column(name = "email", nullable = false)
    private String email = "";

    /** Highest-privilege human role the gateway asserted: Manager, Analyst, or Viewer. */
    @Column(name = "role", nullable = false)
    private String role = "Viewer";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt = LocalDateTime.now();

    public Integer       getId()         { return id; }
    public String        getUuName()     { return uuName; }
    public String        getFirstName()  { return firstName; }
    public String        getLastName()   { return lastName; }
    public String        getEmail()      { return email; }
    public String        getRole()       { return role; }
    public LocalDateTime getCreatedAt()  { return createdAt; }
    public LocalDateTime getUpdatedAt()  { return updatedAt; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }

    public void setId(Integer v)              { this.id = v; }
    public void setUuName(String v)           { this.uuName = v; }
    public void setFirstName(String v)        { this.firstName = v; }
    public void setLastName(String v)         { this.lastName = v; }
    public void setEmail(String v)            { this.email = v; }
    public void setRole(String v)             { this.role = v; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public void setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }
    public void setLastSeenAt(LocalDateTime v){ this.lastSeenAt = v; }

    /** Display name, falling back to the uuName when the gateway sent no first/last name. */
    public String displayName() {
        String full = ((firstName == null ? "" : firstName) + " "
            + (lastName == null ? "" : lastName)).trim();
        return full.isBlank() ? uuName : full;
    }
}
