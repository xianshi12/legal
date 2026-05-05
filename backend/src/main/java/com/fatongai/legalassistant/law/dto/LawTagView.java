package com.fatongai.legalassistant.law.dto;

import java.util.List;

public record LawTagView(
        Long id,
        String code,
        String name,
        String tagType,
        Long parentId,
        List<LawTagView> children
) {
}
