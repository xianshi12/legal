package com.fatongai.legalassistant.chat.dto;

import jakarta.validation.constraints.Size;

public class CreateSessionRequest {
    @Size(max = 200, message = "标题过长")
    private String title;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}

