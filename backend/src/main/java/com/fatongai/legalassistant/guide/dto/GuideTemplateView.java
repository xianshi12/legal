package com.fatongai.legalassistant.guide.dto;

public record GuideTemplateView(
        String type,
        String name,
        String category,
        String limitationType,
        int limitationYears,
        String description
) {
}
