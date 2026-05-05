package com.fatongai.legalassistant.law.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("law_article_text")
public class LawArticleText {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long articleId;
    private String contentRef;
    private String fullContent;
    private String fullInterpretation;
    private String rawHtml;
    private String contentHash;
    private String versionNo;
    private String sourceEtag;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getArticleId() { return articleId; }
    public void setArticleId(Long articleId) { this.articleId = articleId; }
    public String getContentRef() { return contentRef; }
    public void setContentRef(String contentRef) { this.contentRef = contentRef; }
    public String getFullContent() { return fullContent; }
    public void setFullContent(String fullContent) { this.fullContent = fullContent; }
    public String getFullInterpretation() { return fullInterpretation; }
    public void setFullInterpretation(String fullInterpretation) { this.fullInterpretation = fullInterpretation; }
    public String getRawHtml() { return rawHtml; }
    public void setRawHtml(String rawHtml) { this.rawHtml = rawHtml; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getVersionNo() { return versionNo; }
    public void setVersionNo(String versionNo) { this.versionNo = versionNo; }
    public String getSourceEtag() { return sourceEtag; }
    public void setSourceEtag(String sourceEtag) { this.sourceEtag = sourceEtag; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
