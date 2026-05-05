package com.fatongai.legalassistant.contract.dto;

public record ContractRevisionItem(
        String level,
        String location,
        String beforeText,
        String afterText,
        String reason
) {
}
