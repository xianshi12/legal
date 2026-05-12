package com.fatongai.legalassistant.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RegisterRequest(
        @NotBlank(message = "身份不能为空")
        @Pattern(regexp = "^(user|lawyer)$", message = "身份类型不正确")
        String role,

        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请输入有效的中国大陆11位手机号")
        String phone,

        @NotBlank(message = "验证码不能为空")
        @Pattern(regexp = "^\\d{6}$", message = "验证码需为6位数字")
        String code,

        @NotBlank(message = "密码不能为空")
        @Pattern(regexp = "^\\S{8,11}$", message = "密码需为8-11位，且不能包含空格")
        String password,

        String licenseNo,
        Boolean remember
) {
}
