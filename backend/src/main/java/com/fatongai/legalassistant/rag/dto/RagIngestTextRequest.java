package com.fatongai.legalassistant.rag.dto;

public class RagIngestTextRequest {
    private String title;
    private String text;
    private String sourceType;
    private String moduleScope;
    private String businessType;
    private String tags;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getModuleScope() { return moduleScope; }
    public void setModuleScope(String moduleScope) { this.moduleScope = moduleScope; }
    public String getBusinessType() { return businessType; }
    public void setBusinessType(String businessType) { this.businessType = businessType; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
}
