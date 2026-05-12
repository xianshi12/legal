package com.fatongai.legalassistant.auth.controller;

import com.fatongai.legalassistant.auth.dto.CaptchaRequest;
import com.fatongai.legalassistant.auth.dto.LoginRequest;
import com.fatongai.legalassistant.auth.dto.RegisterRequest;
import com.fatongai.legalassistant.auth.dto.ResetPasswordRequest;
import com.fatongai.legalassistant.auth.dto.SmsLoginRequest;
import com.fatongai.legalassistant.auth.service.AuthCaptchaService;
import com.fatongai.legalassistant.auth.service.AuthService;
import com.fatongai.legalassistant.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final AuthCaptchaService captchaService;

    public AuthController(AuthService authService, AuthCaptchaService captchaService) {
        this.authService = authService;
        this.captchaService = captchaService;
    }

    @PostMapping("/captcha")
    public ApiResponse<Void> captcha(@Valid @RequestBody CaptchaRequest req) {
        captchaService.requestCode(req.phone(), req.scene());
        return ApiResponse.ok(null);
    }

    @PostMapping("/register")
    public ApiResponse<?> register(@Valid @RequestBody RegisterRequest req) {
        return ApiResponse.ok(authService.register(req));
    }

    @PostMapping("/login")
    public ApiResponse<?> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.ok(authService.login(req));
    }

    @PostMapping("/sms-login")
    public ApiResponse<?> smsLogin(@Valid @RequestBody SmsLoginRequest req) {
        return ApiResponse.ok(authService.smsLogin(req));
    }

    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        authService.resetPassword(req);
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<?> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.ok(authService.me(extractToken(authorization)));
    }

    private String extractToken(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            throw new IllegalArgumentException("未登录或登录状态已失效");
        }
        return authorization.substring("Bearer ".length()).trim();
    }
}
