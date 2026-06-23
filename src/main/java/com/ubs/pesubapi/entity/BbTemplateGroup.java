package com.ubs.pesubapi.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "bb_template_groups",
       uniqueConstraints = @UniqueConstraint(name = "uq_template_group_header",
                                             columnNames = {"tab_id", "header_text"}))
public class BbTemplateGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tab_id", nullable = false)
    private BbTemplateTab tab;

    @Column(name = "group_sort", nullable = false)
    private int groupSort;

    @Column(name = "header_text", nullable = false, length = 255)
    private String headerText;

    /**
     * Canonical LP Category value inherited by all LP rows beneath this header
     * (e.g. "Rated", "Unrated", "Eligible", "Excluded").
     * Only applied when no per-row classification column is present in the sheet.
     */
    @Column(name = "classification", nullable = false, length = 100)
    private String classification;

    public Integer        getId()             { return id; }
    public BbTemplateTab  getTab()            { return tab; }
    public int            getGroupSort()      { return groupSort; }
    public String         getHeaderText()     { return headerText; }
    public String         getClassification() { return classification; }

    public void setTab(BbTemplateTab v)       { this.tab            = v; }
    public void setGroupSort(int v)           { this.groupSort      = v; }
    public void setHeaderText(String v)       { this.headerText     = v; }
    public void setClassification(String v)   { this.classification = v; }
}
