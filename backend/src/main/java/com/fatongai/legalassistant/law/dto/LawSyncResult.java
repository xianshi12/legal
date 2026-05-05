package com.fatongai.legalassistant.law.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @param skippedItems NPC 同步表示跳过的法规部数；URL 同步表示跳过的页面数。
 */
public record LawSyncResult(
        int fetchedPages,
        int upsertedArticles,
        int embeddedArticles,
        int skippedItems,
        List<String> failedUrls,
        LocalDateTime syncedAt
) {
}
