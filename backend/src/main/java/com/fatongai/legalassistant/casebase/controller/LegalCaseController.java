package com.fatongai.legalassistant.casebase.controller;

import com.fatongai.legalassistant.casebase.service.LegalCaseService;
import com.fatongai.legalassistant.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cases")
public class LegalCaseController {
    private final LegalCaseService legalCaseService;

    public LegalCaseController(LegalCaseService legalCaseService) {
        this.legalCaseService = legalCaseService;
    }

    @GetMapping("/match")
    public ApiResponse<?> match(@RequestParam(value = "q", required = false) String query,
                                @RequestParam(value = "region", required = false) String region,
                                @RequestParam(value = "yearRange", required = false) String yearRange,
                                @RequestParam(value = "courtLevel", required = false) String courtLevel,
                                @RequestParam(value = "page", defaultValue = "1") Integer page,
                                @RequestParam(value = "size", defaultValue = "10") Integer size) {
        return ApiResponse.ok(legalCaseService.match(query, region, yearRange, courtLevel, page, size));
    }

    @GetMapping("/meta")
    public ApiResponse<?> meta() {
        return ApiResponse.ok(legalCaseService.meta());
    }
}
