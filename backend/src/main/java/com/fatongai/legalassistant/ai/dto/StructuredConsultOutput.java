package com.fatongai.legalassistant.ai.dto;

import java.util.ArrayList;
import java.util.List;

public class StructuredConsultOutput {
    private String conclusion;
    private List<String> legalBasis = new ArrayList<>();
    private List<String> actionSteps = new ArrayList<>();
    private List<String> evidenceChecklist = new ArrayList<>();
    private List<String> riskWarnings = new ArrayList<>();
    private List<String> followupQuestions = new ArrayList<>();

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
