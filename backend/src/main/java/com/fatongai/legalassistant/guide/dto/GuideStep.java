package com.fatongai.legalassistant.guide.dto;

public record GuideStep(
        String name,
        String time,
        String desc,
        boolean active,
        boolean warning
) {
}
