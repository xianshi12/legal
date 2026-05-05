package com.fatongai.legalassistant.law.dto;

import java.time.LocalDateTime;
import java.util.List;

public record LawMetaResponse(
        List<String> categories,
        List<String> levels,
        List<String> regions,
        List<String> statuses,
        List<String> issuingBodies,
        long total,
        boolean vectorEnabled,
        String embeddingModel,
        LocalDateTime lastSyncAt,
        /** 国家法律法规数据库公开检索入口（与爬虫 Referer 一致） */
        String npcPublicSearchUrl
) {
}
