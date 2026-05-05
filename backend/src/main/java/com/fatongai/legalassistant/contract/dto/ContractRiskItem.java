package com.fatongai.legalassistant.contract.dto;

import java.util.List;

public record ContractRiskItem(
        String level,
        String clauseTitle,
        String location,
        String originalClause,
        String issue,
        String legalBasis,
        String suggestion,
        String revisedClause,
        List<String> tags
) {
}
