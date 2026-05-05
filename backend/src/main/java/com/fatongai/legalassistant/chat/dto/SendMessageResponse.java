package com.fatongai.legalassistant.chat.dto;

import java.util.List;

public class SendMessageResponse {
    private String sessionId;
    private String sessionTitle;
    /** 本轮用户消息的 id（用于前端对齐附件预览） */
    private String userMessageId;
    private List<ChatMessageAttachment> userAttachments;
    private String messageId;
    private String answer;
    private String scenario;
    private String conclusion;
    private List<String> legalBasis;
    private List<String> actionSteps;
    private List<String> evidenceChecklist;
    private List<String> riskWarnings;
    private List<String> followupQuestions;
    private List<String> references;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSessionTitle() {
        return sessionTitle;
    }

    public void setSessionTitle(String sessionTitle) {
        this.sessionTitle = sessionTitle;
    }

    public String getUserMessageId() {
        return userMessageId;
    }

    public void setUserMessageId(String userMessageId) {
        this.userMessageId = userMessageId;
    }

    public List<ChatMessageAttachment> getUserAttachments() {
        return userAttachments;
    }

    public void setUserAttachments(List<ChatMessageAttachment> userAttachments) {
        this.userAttachments = userAttachments;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getScenario() {
        return scenario;
    }

    public void setScenario(String scenario) {
        this.scenario = scenario;
    }

    public List<String> getReferences() {
        return references;
    }

    public void setReferences(List<String> references) {
        this.references = references;
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
}
