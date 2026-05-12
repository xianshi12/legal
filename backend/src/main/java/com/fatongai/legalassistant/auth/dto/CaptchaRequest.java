package com.fatongai.legalassistant.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CaptchaRequest(
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请输入有效的中国大陆11位手机号")
        String phone,

        @NotBlank(message = "场景不能为空")
        @Pattern(regexp = "^(login|register|reset-password)$", message = "验证码场景不正确")
        String scene
) {
}
