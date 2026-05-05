package com.fatongai.legalassistant.contract.controller;

import com.fatongai.legalassistant.common.ApiResponse;
import com.fatongai.legalassistant.contract.service.QwenContractReviewService;
import com.fatongai.legalassistant.file.FileStorageService;
import com.fatongai.legalassistant.file.TextExtractService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;

@RestController
@RequestMapping("/api/contracts")
public class ContractReviewController {
    private static final Logger log = LoggerFactory.getLogger(ContractReviewController.class);
    private final QwenContractReviewService reviewService;
    private final FileStorageService fileStorageService;
    private final TextExtractService textExtractService;

    public ContractReviewController(QwenContractReviewService reviewService,
                                    FileStorageService fileStorageService,
                                    TextExtractService textExtractService) {
        this.reviewService = reviewService;
        this.fileStorageService = fileStorageService;
        this.textExtractService = textExtractService;
    }

    @PostMapping(value = "/review", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<?> review(@RequestParam(value = "contractName", required = false) String contractName,
                                 @RequestParam(value = "contractType", required = false) String contractType,
                                 @RequestParam(value = "reviewMode", defaultValue = "full") String reviewMode,
                                 @RequestParam(value = "reviewFocus", required = false) String reviewFocus,
                                 @RequestParam(value = "rawText", required = false) String rawText,
                                 @RequestPart(value = "file", required = false) MultipartFile file) throws Exception {
        String text = rawText;
        String name = contractName;
        boolean hasUpload = file != null && !file.isEmpty();
        log.info("[contract-review] 请求: 上传={}, 粘贴字数={}, contractName={}",
                hasUpload,
                rawText != null ? rawText.length() : 0,
                contractName);
        if (hasUpload) {
            var stored = fileStorageService.store(file);
            if (!StringUtils.hasText(name)) {
                name = stored.originalName();
            }
            try (FileInputStream in = new FileInputStream(stored.path())) {
                text = textExtractService.extract(in, stored.originalName());
            }
        }
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("请上传 Word/PDF 合同或粘贴合同正文");
        }
        log.info("[contract-review] 送审正文长度: {} 字，合同名={}", text.length(), name);
        var result = reviewService.review(name, contractType, reviewMode, reviewFocus, text);
        log.info("[contract-review] 审查完成: overallRisk={}, 风险条数={}, model={}",
                result.overallRisk(),
                result.risks() != null ? result.risks().size() : 0,
                result.model());
        return ApiResponse.ok(result);
    }
}
