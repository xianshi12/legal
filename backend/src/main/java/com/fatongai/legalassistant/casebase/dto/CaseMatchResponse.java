package com.fatongai.legalassistant.casebase.dto;

import java.util.List;

public record CaseMatchResponse(
        String query,
        String region,
        String yearRange,
        String courtLevel,
        long total,
        boolean vectorEnabled,
        List<CaseMatchResult> items,
        List<String> ragReferences
) {
}
