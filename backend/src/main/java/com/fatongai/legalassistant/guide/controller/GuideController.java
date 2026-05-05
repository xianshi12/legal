package com.fatongai.legalassistant.guide.controller;

import com.fatongai.legalassistant.common.ApiResponse;
import com.fatongai.legalassistant.guide.dto.GuideRequest;
import com.fatongai.legalassistant.guide.service.GuideService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/guides")
public class GuideController {
    private final GuideService guideService;

    public GuideController(GuideService guideService) {
        this.guideService = guideService;
    }

    @GetMapping("/templates")
    public ApiResponse<?> templates() {
        return ApiResponse.ok(guideService.templates());
    }

    @PostMapping("/generate")
    public ApiResponse<?> generate(@RequestBody GuideRequest request) {
        return ApiResponse.ok(guideService.guide(request));
    }

    @GetMapping("/limitation")
    public ApiResponse<?> limitation(@RequestParam("limitationType") String limitationType,
                                     @RequestParam("incidentDate") String incidentDate) {
        return ApiResponse.ok(guideService.limitation(limitationType, incidentDate));
    }
}
