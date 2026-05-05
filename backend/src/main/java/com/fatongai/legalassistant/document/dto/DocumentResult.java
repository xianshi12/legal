package com.fatongai.legalassistant.document.dto;

import java.time.LocalDateTime;
import java.util.List;

public record DocumentResult(
        String documentType,
        String title,
        String outputMode,
        String status,
        String content,
        List<String> requiredFields,
        List<String> missingFields,
        List<String> clauseTips,
        List<String> optimizationSuggestions,
        List<String> riskAnnotations,
        List<String> evidenceChecklist,
        List<String> ragReferences,
        LocalDateTime generatedAt
) {
}
