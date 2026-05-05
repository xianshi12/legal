package com.fatongai.legalassistant.casebase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("legal_case")
public class LegalCase {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String caseNo;
    private String title;
    private String caseType;
    private String cause;
    private String region;
    private String courtName;
    private String courtLevel;
    private String trialLevel;
    private LocalDate judgmentDate;
    private String judgmentResult;
    private String adjudicationPoints;
    private String keyEvidence;
    private String facts;
    private String sourceName;
    private String sourceUrl;
    private String vectorModel;
    private String vectorJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCaseNo() { return caseNo; }
    public void setCaseNo(String caseNo) { this.caseNo = caseNo; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCaseType() { return caseType; }
    public void setCaseType(String caseType) { this.caseType = caseType; }
    public String getCause() { return cause; }
    public void setCause(String cause) { this.cause = cause; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getCourtName() { return courtName; }
    public void setCourtName(String courtName) { this.courtName = courtName; }
    public String getCourtLevel() { return courtLevel; }
    public void setCourtLevel(String courtLevel) { this.courtLevel = courtLevel; }
    public String getTrialLevel() { return trialLevel; }
    public void setTrialLevel(String trialLevel) { this.trialLevel = trialLevel; }
    public LocalDate getJudgmentDate() { return judgmentDate; }
    public void setJudgmentDate(LocalDate judgmentDate) { this.judgmentDate = judgmentDate; }
    public String getJudgmentResult() { return judgmentResult; }
    public void setJudgmentResult(String judgmentResult) { this.judgmentResult = judgmentResult; }
    public String getAdjudicationPoints() { return adjudicationPoints; }
    public void setAdjudicationPoints(String adjudicationPoints) { this.adjudicationPoints = adjudicationPoints; }
    public String getKeyEvidence() { return keyEvidence; }
    public void setKeyEvidence(String keyEvidence) { this.keyEvidence = keyEvidence; }
    public String getFacts() { return facts; }
    public void setFacts(String facts) { this.facts = facts; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getVectorModel() { return vectorModel; }
    public void setVectorModel(String vectorModel) { this.vectorModel = vectorModel; }
    public String getVectorJson() { return vectorJson; }
    public void setVectorJson(String vectorJson) { this.vectorJson = vectorJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
