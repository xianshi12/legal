package com.fatongai.legalassistant.contract.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record ContractReviewResponse(
        String contractName,
        String contractType,
        String reviewMode,
        String model,
        String overallRisk,
        String summary,
        Map<String, Integer> riskStats,
        List<ContractRiskItem> risks,
        List<ContractRevisionItem> revisions,
        String revisedText,
        List<String> checklist,
        List<String> ragReferences,
        /** 本次审查使用的合同正文（与上传/粘贴一致），供前端标注高亮 */
        String sourceText,
        LocalDateTime reviewedAt
) {
}
