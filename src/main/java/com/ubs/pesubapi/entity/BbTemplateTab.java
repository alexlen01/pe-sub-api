package com.ubs.pesubapi.entity;

import com.ubs.pesubapi.entity.converter.StringListConverter;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "bb_template_tabs",
       uniqueConstraints = @UniqueConstraint(name = "uq_template_tab_role",
                                             columnNames = {"template_id", "tab_role"}))
public class BbTemplateTab {

    public enum TabRole { LP_GRID, CONCENTRATION, CAPITAL_CALL, TOP_SHEET }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    private BbTemplate template;

    @Enumerated(EnumType.STRING)
    @Column(name = "tab_role", nullable = false, length = 50)
    private TabRole tabRole;

    @Column(name = "tab_sort", nullable = false)
    private int tabSort = 1;

    @Column(name = "sheet_name")
    private String sheetName;

    @Column(name = "header_row_index")
    private Integer headerRowIndex;

    @Convert(converter = StringListConverter.class)
    @Column(name = "skip_row_keywords", columnDefinition = "jsonb")
    private List<String> skipRowKeywords = List.of("Total", "Subtotal", "Sub-Total", "Grand Total", "Sum", "Net Total");

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }

    public Integer       getId()              { return id; }
    public BbTemplate    getTemplate()        { return template; }
    public TabRole       getTabRole()         { return tabRole; }
    public int           getTabSort()         { return tabSort; }
    public String        getSheetName()       { return sheetName; }
    public Integer       getHeaderRowIndex()  { return headerRowIndex; }
    public List<String>  getSkipRowKeywords() { return skipRowKeywords; }
    public LocalDateTime getCreatedAt()       { return createdAt; }

    public void setTemplate(BbTemplate v)         { this.template        = v; }
    public void setTabRole(TabRole v)             { this.tabRole         = v; }
    public void setTabSort(int v)                 { this.tabSort         = v; }
    public void setSheetName(String v)            { this.sheetName       = v; }
    public void setHeaderRowIndex(Integer v)      { this.headerRowIndex  = v; }
    public void setSkipRowKeywords(List<String> v){ this.skipRowKeywords = v; }
}
