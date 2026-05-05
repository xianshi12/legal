package com.fatongai.legalassistant.guide.dto;

import java.time.LocalDateTime;
import java.util.List;

public record GuideResponse(
        String flowType,
        String title,
        String category,
        String summary,
        List<GuideStep> steps,
        List<String> materials,
        List<String> attentionPoints,
        List<String> evidenceChecklist,
        LimitationResult limitation,
        boolean aiGenerated,
        List<String> ragReferences,
        LocalDateTime generatedAt
) {
}
