package com.ubs.pesubapi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "bb_template_files")
public class BbTemplateFile {

    @Id
    @Column(name = "template_id")
    private Integer templateId;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "content", nullable = false, columnDefinition = "bytea")
    private byte[] content;

    public Integer getTemplateId()  { return templateId; }
    public String getContentType()  { return contentType; }
    public byte[] getContent()      { return content; }

    public void setTemplateId(Integer v) { this.templateId = v; }
    public void setContentType(String v) { this.contentType = v; }
    public void setContent(byte[] v)     { this.content = v; }
}
