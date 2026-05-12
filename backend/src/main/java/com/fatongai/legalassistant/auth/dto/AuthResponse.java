package com.fatongai.legalassistant.auth.dto;

public record AuthResponse(String token, AuthUserView user) {
}
