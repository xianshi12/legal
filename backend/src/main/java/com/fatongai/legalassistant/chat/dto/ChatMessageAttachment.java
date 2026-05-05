package com.fatongai.legalassistant.chat.dto;

/**
 * 用户消息中随对话保存的附件元数据（用于历史记录预览；实际文件经 {@code /api/chat/files/{fileId}} 读取）。
 */
public class ChatMessageAttachment {

    private String fileId;
    private String originalName;
    private String contentType;
    private long size;

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }
}
