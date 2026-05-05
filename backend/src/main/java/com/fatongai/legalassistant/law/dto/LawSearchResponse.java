package com.fatongai.legalassistant.law.dto;

import java.time.LocalDateTime;
import java.util.List;

public record LawSearchResponse(
        String query,
        String searchType,
        int page,
        int size,
        long total,
        boolean vectorEnabled,
        LocalDateTime lastSyncAt,
        List<LawSearchResult> items,
        List<String> ragReferences
) {
}
