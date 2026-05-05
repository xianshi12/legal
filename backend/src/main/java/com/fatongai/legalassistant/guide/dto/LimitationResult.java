package com.fatongai.legalassistant.guide.dto;

import java.time.LocalDate;

public record LimitationResult(
        String limitationType,
        LocalDate startDate,
        LocalDate deadline,
        long daysLeft,
        String status,
        String note,
        /** 简短规则标签，便于界面展示，如「仲裁申请时效：1年」 */
        String ruleSummary
) {
}
