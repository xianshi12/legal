package com.fatongai.legalassistant.casebase.dto;

import com.fatongai.legalassistant.casebase.entity.LegalCase;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

public record CaseMatchResult(
        Long id,
        String caseNo,
        String title,
        String caseType,
        String cause,
        String region,
        String courtName,
        String courtLevel,
        String trialLevel,
        LocalDate judgmentDate,
        String judgmentYear,
        String judgmentResult,
        String adjudicationPoints,
        List<String> keyEvidence,
        String facts,
        String sourceName,
        String sourceUrl,
        double similarity,
        String similarityLabel
) {
    public static CaseMatchResult of(LegalCase legalCase, double similarity) {
        return new CaseMatchResult(
                legalCase.getId(),
                legalCase.getCaseNo(),
                legalCase.getTitle(),
                legalCase.getCaseType(),
                legalCase.getCause(),
                legalCase.getRegion(),
                legalCase.getCourtName(),
                legalCase.getCourtLevel(),
                legalCase.getTrialLevel(),
                legalCase.getJudgmentDate(),
                legalCase.getJudgmentDate() == null ? "" : legalCase.getJudgmentDate().getYear() + "年",
                legalCase.getJudgmentResult(),
                legalCase.getAdjudicationPoints(),
                split(legalCase.getKeyEvidence()),
                legalCase.getFacts(),
                legalCase.getSourceName(),
                legalCase.getSourceUrl(),
                Math.round(similarity * 10000.0) / 10000.0,
                similarity >= 0.82 ? "高度相似" : similarity >= 0.62 ? "较相似" : "可参考"
        );
    }

    private static List<String> split(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.split("[,，;；、\\n]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }
}
