package com.fatongai.legalassistant.law.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("law_article")
public class LawArticle {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String lawName;
    private String articleNo;
    private String title;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String content;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String interpretation;
    private String scenarios;
    @TableField("exceptions_text")
    private String exceptionsText;
    private String causeOfAction;
    private String category;
    private String level;
    /** 制定机关 */
    private String issuingBody;
    private String region;
    private String status;
    private String sourceName;
    private String sourceUrl;
    /** 轻量映射：如 {@code npc:bbbsId}，正文按此键从外部数据源解析，不入库 */
    @TableField("external_ref")
    private String externalRef;
    private String stableArticleKey;
    private String normalizedArticleNo;
    private Integer articleOrdinal;
    private String articleSummary;
    private String interpretationSummary;
    private String keywordIndex;
    private Integer sortWeight;
    private String versionNo;
    private LocalDate effectiveTo;
    private Long replacedByArticleId;
    private Boolean currentEffective;
    private String contentHash;
    private String contentRef;
    private LocalDate publishDate;
    private LocalDate effectiveDate;
    private LocalDateTime sourceUpdatedAt;
    private String vectorModel;
    private String vectorJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getLawName() { return lawName; }
    public void setLawName(String lawName) { this.lawName = lawName; }
    public String getArticleNo() { return articleNo; }
    public void setArticleNo(String articleNo) { this.articleNo = articleNo; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getInterpretation() { return interpretation; }
    public void setInterpretation(String interpretation) { this.interpretation = interpretation; }
    public String getScenarios() { return scenarios; }
    public void setScenarios(String scenarios) { this.scenarios = scenarios; }
    public String getExceptionsText() { return exceptionsText; }
    public void setExceptionsText(String exceptionsText) { this.exceptionsText = exceptionsText; }
    public String getCauseOfAction() { return causeOfAction; }
    public void setCauseOfAction(String causeOfAction) { this.causeOfAction = causeOfAction; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getIssuingBody() { return issuingBody; }
    public void setIssuingBody(String issuingBody) { this.issuingBody = issuingBody; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getExternalRef() { return externalRef; }
    public void setExternalRef(String externalRef) { this.externalRef = externalRef; }
    public String getStableArticleKey() { return stableArticleKey; }
    public void setStableArticleKey(String stableArticleKey) { this.stableArticleKey = stableArticleKey; }
    public String getNormalizedArticleNo() { return normalizedArticleNo; }
    public void setNormalizedArticleNo(String normalizedArticleNo) { this.normalizedArticleNo = normalizedArticleNo; }
    public Integer getArticleOrdinal() { return articleOrdinal; }
    public void setArticleOrdinal(Integer articleOrdinal) { this.articleOrdinal = articleOrdinal; }
    public String getArticleSummary() { return articleSummary; }
    public void setArticleSummary(String articleSummary) { this.articleSummary = articleSummary; }
    public String getInterpretationSummary() { return interpretationSummary; }
    public void setInterpretationSummary(String interpretationSummary) { this.interpretationSummary = interpretationSummary; }
    public String getKeywordIndex() { return keywordIndex; }
    public void setKeywordIndex(String keywordIndex) { this.keywordIndex = keywordIndex; }
    public Integer getSortWeight() { return sortWeight; }
    public void setSortWeight(Integer sortWeight) { this.sortWeight = sortWeight; }
    public String getVersionNo() { return versionNo; }
    public void setVersionNo(String versionNo) { this.versionNo = versionNo; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(LocalDate effectiveTo) { this.effectiveTo = effectiveTo; }
    public Long getReplacedByArticleId() { return replacedByArticleId; }
    public void setReplacedByArticleId(Long replacedByArticleId) { this.replacedByArticleId = replacedByArticleId; }
    public Boolean getCurrentEffective() { return currentEffective; }
    public void setCurrentEffective(Boolean currentEffective) { this.currentEffective = currentEffective; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getContentRef() { return contentRef; }
    public void setContentRef(String contentRef) { this.contentRef = contentRef; }
    public LocalDate getPublishDate() { return publishDate; }
    public void setPublishDate(LocalDate publishDate) { this.publishDate = publishDate; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }
    public LocalDateTime getSourceUpdatedAt() { return sourceUpdatedAt; }
    public void setSourceUpdatedAt(LocalDateTime sourceUpdatedAt) { this.sourceUpdatedAt = sourceUpdatedAt; }
    public String getVectorModel() { return vectorModel; }
    public void setVectorModel(String vectorModel) { this.vectorModel = vectorModel; }
    public String getVectorJson() { return vectorJson; }
    public void setVectorJson(String vectorJson) { this.vectorJson = vectorJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
