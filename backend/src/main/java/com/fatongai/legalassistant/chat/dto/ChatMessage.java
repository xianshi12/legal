package com.fatongai.legalassistant.chat.dto;

import java.time.LocalDateTime;
import java.util.List;

public class ChatMessage {
    private String id;
    private String role; // user | assistant | system
    private String content;
    private String scenario;
    private String conclusion;
    private List<String> legalBasis;
    private List<String> actionSteps;
    private List<String> evidenceChecklist;
    private List<String> riskWarnings;
    private List<String> followupQuestions;
    private List<String> references;
    /** 本条用户消息上传的附件（仅 role=user 时有值） */
    private List<ChatMessageAttachment> attachments;
    private LocalDateTime createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<String> getReferences() {
        return references;
    }

    public void setReferences(List<String> references) {
        this.references = references;
    }

    public List<ChatMessageAttachment> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<ChatMessageAttachment> attachments) {
        this.attachments = attachments;
    }

    public String getScenario() {
        return scenario;
    }

    public void setScenario(String scenario) {
        this.scenario = scenario;
    }

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
    }

    public List<String> getLegalBasis() {
        return legalBasis;
    }

    public void setLegalBasis(List<String> legalBasis) {
        this.legalBasis = legalBasis;
    }

    public List<String> getActionSteps() {
        return actionSteps;
    }

    public void setActionSteps(List<String> actionSteps) {
        this.actionSteps = actionSteps;
    }

    public List<String> getEvidenceChecklist() {
        return evidenceChecklist;
    }

    public void setEvidenceChecklist(List<String> evidenceChecklist) {
        this.evidenceChecklist = evidenceChecklist;
    }

    public List<String> getRiskWarnings() {
        return riskWarnings;
    }

    public void setRiskWarnings(List<String> riskWarnings) {
        this.riskWarnings = riskWarnings;
    }

    public List<String> getFollowupQuestions() {
        return followupQuestions;
    }

    public void setFollowupQuestions(List<String> followupQuestions) {
        this.followupQuestions = followupQuestions;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

