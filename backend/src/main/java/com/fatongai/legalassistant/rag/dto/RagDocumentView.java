package com.fatongai.legalassistant.rag.dto;

import com.fatongai.legalassistant.rag.entity.RagDocument;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

public record RagDocumentView(
        String docId,
        String title,
        String originalName,
        String sourceType,
        String moduleScope,
        String businessType,
        List<String> tags,
        String contentType,
        Long fileSize,
        String status,
        Integer chunkCount,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static RagDocumentView of(RagDocument doc) {
        return new RagDocumentView(
                doc.getDocId(),
                doc.getTitle(),
                doc.getOriginalName(),
                doc.getSourceType(),
                doc.getModuleScope(),
                doc.getBusinessType(),
                split(doc.getTags()),
                doc.getContentType(),
                doc.getFileSize(),
                doc.getStatus(),
                doc.getChunkCount(),
                doc.getErrorMessage(),
                doc.getCreatedAt(),
                doc.getUpdatedAt()
        );
    }

    private static List<String> split(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.split("[,，;；、\\n]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }
}
