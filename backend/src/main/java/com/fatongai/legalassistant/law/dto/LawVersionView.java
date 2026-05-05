package com.fatongai.legalassistant.law.dto;

import java.time.LocalDate;

public record LawVersionView(
        Long id,
        String lawName,
        String articleNo,
        String status,
        String versionNo,
        LocalDate effectiveDate,
        LocalDate effectiveTo,
        boolean currentEffective,
        String sourceName,
        String sourceUrl
) {
}
