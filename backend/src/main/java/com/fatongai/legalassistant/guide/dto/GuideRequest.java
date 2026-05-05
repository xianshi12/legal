package com.fatongai.legalassistant.guide.dto;

public class GuideRequest {
    private String flowType;
    private String customFlow;
    private String facts;
    private String incidentDate;
    private String limitationType;

    public String getFlowType() { return flowType; }
    public void setFlowType(String flowType) { this.flowType = flowType; }
    public String getCustomFlow() { return customFlow; }
    public void setCustomFlow(String customFlow) { this.customFlow = customFlow; }
    public String getFacts() { return facts; }
    public void setFacts(String facts) { this.facts = facts; }
    public String getIncidentDate() { return incidentDate; }
    public void setIncidentDate(String incidentDate) { this.incidentDate = incidentDate; }
    public String getLimitationType() { return limitationType; }
    public void setLimitationType(String limitationType) { this.limitationType = limitationType; }
}
