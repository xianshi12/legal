package com.fatongai.legalassistant.document.dto;

import java.util.List;

public record DocumentTemplateView(
        String type,
        String name,
        String category,
        String scenario,
        List<String> fields,
        List<String> clauseTips,
        boolean uploadSupported
) {
}
