package com.fatongai.legalassistant.casebase.dto;

import java.util.List;

public record CaseMetaResponse(
        List<String> regions,
        List<String> yearRanges,
        List<String> courtLevels,
        long total,
        boolean vectorEnabled,
        String embeddingModel
) {
}
