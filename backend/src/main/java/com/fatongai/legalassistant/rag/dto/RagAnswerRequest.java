package com.fatongai.legalassistant.rag.dto;

public class RagAnswerRequest {
    private String question;
    private String moduleScope;
    private String businessType;
    private Integer limit;

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getModuleScope() { return moduleScope; }
    public void setModuleScope(String moduleScope) { this.moduleScope = moduleScope; }
    public String getBusinessType() { return businessType; }
    public void setBusinessType(String businessType) { this.businessType = businessType; }
    public Integer getLimit() { return limit; }
    public void setLimit(Integer limit) { this.limit = limit; }
}
