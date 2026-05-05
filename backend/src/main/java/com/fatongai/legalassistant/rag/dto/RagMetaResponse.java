package com.fatongai.legalassistant.rag.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record RagMetaResponse(
        long documents,
        long chunks,
        boolean vectorEnabled,
        String embeddingModel,
        LocalDateTime lastUpdatedAt,
        List<String> moduleScopes,
        List<String> businessTypes,
        Map<String, Long> statusStats
) {
}
