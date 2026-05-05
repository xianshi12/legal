package com.fatongai.legalassistant.document.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public class DocumentGenerateRequest {
    @NotBlank(message = "文书类型不能为空")
    private String documentType;
    private String category;
    private String scenario;
    private String outputMode;
    private Map<String, String> fields;
    private String facts;
    private String extraRequirements;

    public String getDocumentType() {
        return documentType;
    }

    public void setDocumentType(String documentType) {
        this.documentType = documentType;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getScenario() {
        return scenario;
    }

    public void setScenario(String scenario) {
        this.scenario = scenario;
    }

    public String getOutputMode() {
        return outputMode;
    }

    public void setOutputMode(String outputMode) {
        this.outputMode = outputMode;
    }

    public Map<String, String> getFields() {
        return fields;
    }

    public void setFields(Map<String, String> fields) {
        this.fields = fields;
    }

    public String getFacts() {
        return facts;
    }

    public void setFacts(String facts) {
        this.facts = facts;
    }

    public String getExtraRequirements() {
        return extraRequirements;
    }

    public void setExtraRequirements(String extraRequirements) {
        this.extraRequirements = extraRequirements;
    }
}
