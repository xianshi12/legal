package com.fatongai.legalassistant.rag.dto;

import java.util.List;

public record RagSearchResponse(
        String query,
        String moduleScope,
        String businessType,
        int limit,
        long total,
        boolean vectorEnabled,
        List<RagSearchResult> items
) {
}
