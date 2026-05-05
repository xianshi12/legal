package com.fatongai.legalassistant.rag.dto;

import com.fatongai.legalassistant.rag.entity.RagDocument;
import com.fatongai.legalassistant.rag.entity.RagDocumentChunk;

import java.time.LocalDateTime;

public record RagSearchResult(
        Long chunkId,
        String docId,
        Integer chunkIndex,
        String title,
        String documentTitle,
        String content,
        String summary,
        String keywords,
        String sourceType,
        String moduleScope,
        String businessType,
        double score,
        String scoreLabel,
        LocalDateTime updatedAt
) {
    public static RagSearchResult of(RagDocumentChunk chunk, RagDocument doc, double score) {
        String documentTitle = doc == null ? chunk.getTitle() : doc.getTitle();
        return new RagSearchResult(
                chunk.getId(),
                chunk.getDocId(),
                chunk.getChunkIndex(),
                chunk.getTitle(),
                documentTitle,
                chunk.getContent(),
                chunk.getSummary(),
                chunk.getKeywords(),
                chunk.getSourceType(),
                chunk.getModuleScope(),
                chunk.getBusinessType(),
                Math.round(score * 10000.0) / 10000.0,
                score >= 0.72 ? "高度相关" : score >= 0.45 ? "相关" : "可参考",
                chunk.getUpdatedAt()
        );
    }

    public String toReferenceLine() {
        return "【RAG】" + documentTitle + " / 片段" + chunkIndex + "（" + scoreLabel + "）";
    }
}
