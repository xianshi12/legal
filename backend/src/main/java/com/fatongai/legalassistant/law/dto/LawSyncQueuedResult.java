package com.fatongai.legalassistant.law.dto;

import java.time.LocalDateTime;

public record LawSyncQueuedResult(
        boolean queued,
        String taskId,
        String provider,
        String status,
        String message,
        LocalDateTime enqueuedAt
) {
}
