package com.fatongai.legalassistant.auth.dto;

public record AuthUserView(Long id, String role, String phone, String displayName, String licenseNo) {
}
