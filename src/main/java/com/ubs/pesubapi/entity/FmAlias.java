package com.ubs.pesubapi.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "fm_aliases")
public class FmAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "canonical_field_id", nullable = false)
    private Integer canonicalFieldId;

    @Column(name = "alias_sort", nullable = false)
    private Integer aliasSort;

    @Column(name = "alias_text", nullable = false)
    private String aliasText;

    @Column(nullable = false)
    private String tier;

    private String bank;

    public Integer getId()               { return id; }
    public Integer getCanonicalFieldId() { return canonicalFieldId; }
    public Integer getAliasSort()        { return aliasSort; }
    public String  getAliasText()        { return aliasText; }
    public String  getTier()             { return tier; }
    public String  getBank()             { return bank; }

    public void setCanonicalFieldId(Integer v) { this.canonicalFieldId = v; }
    public void setAliasSort(Integer v)        { this.aliasSort = v; }
    public void setAliasText(String v)         { this.aliasText = v; }
    public void setTier(String v)              { this.tier = v; }
    public void setBank(String v)              { this.bank = v; }
}
