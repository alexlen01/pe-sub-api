package com.ubs.pesubapi.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "fm_canonical_fields")
public class FmCanonicalField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "group_name", nullable = false)
    private String groupName;

    @Column(name = "group_sort", nullable = false)
    private Integer groupSort;

    @Column(name = "field_sort", nullable = false)
    private Integer fieldSort;

    @Column(nullable = false)
    private String canonical;

    @Column(name = "lp_master_field", nullable = false)
    private String lpMasterField;

    private String disambiguation;

    @Column(name = "extraction_key")
    private String extractionKey;

    public Integer getId()            { return id; }
    public String  getGroupName()     { return groupName; }
    public Integer getGroupSort()     { return groupSort; }
    public Integer getFieldSort()     { return fieldSort; }
    public String  getCanonical()     { return canonical; }
    public String  getLpMasterField() { return lpMasterField; }
    public String  getDisambiguation(){ return disambiguation; }
    public String  getExtractionKey() { return extractionKey; }
}
