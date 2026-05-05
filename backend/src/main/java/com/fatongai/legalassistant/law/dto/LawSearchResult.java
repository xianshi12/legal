package com.fatongai.legalassistant.law.dto;

import com.fatongai.legalassistant.law.entity.LawArticle;
import com.fatongai.legalassistant.law.support.LawInterpretationTexts;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

public record LawSearchResult(
        Long id,
        String lawName,
        String articleNo,
        String title,
        String content,
        String interpretation,
        List<String> scenarios,
        String exceptionsText,
        List<String> causeOfAction,
        String category,
        String level,
        String issuingBody,
        String region,
        String status,
        String sourceName,
        String sourceUrl,
        String versionNo,
        LocalDate effectiveTo,
        Boolean currentEffective,
        Long replacedByArticleId,
        String contentHash,
        String contentRef,
        boolean detailLoaded,
        String disclaimer,
        String validityNotice,
        LocalDate publishDate,
        LocalDate effectiveDate,
        LocalDateTime sourceUpdatedAt,
        double score,
        String scoreLabel
) {
    public static LawSearchResult of(LawArticle law, double score) {
        return new LawSearchResult(
                law.getId(),
                law.getLawName(),
                law.getArticleNo(),
                law.getTitle(),
                law.getContent(),
                LawInterpretationTexts.extractCoreInterpretation(law.getInterpretation()),
                split(law.getScenarios()),
                law.getExceptionsText(),
                split(law.getCauseOfAction()),
                law.getCategory(),
                law.getLevel(),
                law.getIssuingBody(),
                law.getRegion(),
                law.getStatus(),
                law.getSourceName(),
                law.getSourceUrl(),
                law.getVersionNo(),
                law.getEffectiveTo(),
                law.getCurrentEffective(),
                law.getReplacedByArticleId(),
                law.getContentHash(),
                law.getContentRef(),
                false,
                "通俗解读仅用于辅助理解，不构成法律意见；正式引用请以来源机关公布文本为准。",
                null,
                law.getPublishDate(),
                law.getEffectiveDate(),
                law.getSourceUpdatedAt(),
                Math.round(score * 10000.0) / 10000.0,
                score >= 0.72 ? "高度相关" : score >= 0.45 ? "相关" : "可参考"
        );
    }

    /** 覆盖通俗解读字段（例如详情接口按来源 URL 现算） */
    public LawSearchResult withInterpretation(String newInterpretation) {
        return new LawSearchResult(
                id,
                lawName,
                articleNo,
                title,
                content,
                newInterpretation,
                scenarios,
                exceptionsText,
                causeOfAction,
                category,
                level,
                issuingBody,
                region,
                status,
                sourceName,
                sourceUrl,
                versionNo,
                effectiveTo,
                currentEffective,
                replacedByArticleId,
                contentHash,
                contentRef,
                detailLoaded,
                disclaimer,
                validityNotice,
                publishDate,
                effectiveDate,
                sourceUpdatedAt,
                score,
                scoreLabel
        );
    }

    public LawSearchResult withValidityNotice(String notice) {
        return new LawSearchResult(
                id,
                lawName,
                articleNo,
                title,
                content,
                interpretation,
                scenarios,
                exceptionsText,
                causeOfAction,
                category,
                level,
                issuingBody,
                region,
                status,
                sourceName,
                sourceUrl,
                versionNo,
                effectiveTo,
                currentEffective,
                replacedByArticleId,
                contentHash,
                contentRef,
                detailLoaded,
                disclaimer,
                notice,
                publishDate,
                effectiveDate,
                sourceUpdatedAt,
                score,
                scoreLabel
        );
    }

    /** 列表/详情：写入映射解析后的条文摘录与通俗解读（不入库） */
    public LawSearchResult withContentAndInterpretation(String newContent, String newInterpretation) {
        return withContentAndInterpretation(newContent, newInterpretation, newContent != null && newContent.length() > 600);
    }

    public LawSearchResult withContentAndInterpretation(String newContent, String newInterpretation, boolean loadedDetail) {
        return new LawSearchResult(
                id,
                lawName,
                articleNo,
                title,
                newContent,
                newInterpretation,
                scenarios,
                exceptionsText,
                causeOfAction,
                category,
                level,
                issuingBody,
                region,
                status,
                sourceName,
                sourceUrl,
                versionNo,
                effectiveTo,
                currentEffective,
                replacedByArticleId,
                contentHash,
                contentRef,
                loadedDetail,
                disclaimer,
                validityNotice,
                publishDate,
                effectiveDate,
                sourceUpdatedAt,
                score,
                scoreLabel
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
