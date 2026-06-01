package com.ubs.pesubapi.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "fm_blocklist")
public class FmBlocklistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String qualifier;

    @Column(nullable = false)
    private String reason;

    public Integer getId()        { return id; }
    public String  getQualifier() { return qualifier; }
    public String  getReason()    { return reason; }
}
