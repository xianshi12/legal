package com.fatongai.legalassistant.chat.dto;

/**
 * 附件文本提取结果（智能咨询预识别 / 前端展示）
 */
public record AttachmentExtractItem(String filename, String text, boolean ok, String error) {
}
