package com.fatongai.legalassistant.auth.dto;

import java.time.LocalDateTime;

public record CaptchaMessage(String phone, String scene, LocalDateTime requestedAt) {
}
