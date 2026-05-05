package com.fatongai.legalassistant.rag.dto;

import java.time.LocalDateTime;
import java.util.List;

public record RagAnswerResponse(
        String question,
        String answer,
        List<RagSearchResult> references,
        boolean aiEnabled,
        LocalDateTime answeredAt
) {
}
