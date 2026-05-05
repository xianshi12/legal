package com.fatongai.legalassistant.law.dto;

import java.time.LocalDateTime;

public class LawSyncJobMessage {
    private String taskId;
    private String provider;
    private LawSyncRequest request;
    private LocalDateTime enqueuedAt;

    public LawSyncJobMessage() {
    }

    public LawSyncJobMessage(String taskId, String provider, LawSyncRequest request, LocalDateTime enqueuedAt) {
        this.taskId = taskId;
        this.provider = provider;
        this.request = request;
        this.enqueuedAt = enqueuedAt;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public LawSyncRequest getRequest() {
        return request;
    }

    public void setRequest(LawSyncRequest request) {
        this.request = request;
    }

    public LocalDateTime getEnqueuedAt() {
        return enqueuedAt;
    }

    public void setEnqueuedAt(LocalDateTime enqueuedAt) {
        this.enqueuedAt = enqueuedAt;
    }
}
