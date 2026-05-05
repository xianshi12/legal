package com.fatongai.legalassistant.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class DocumentExportRequest {

    @NotBlank
    @Pattern(regexp = "(?i)TXT|DOCX|PDF", message = "format 仅支持 TXT、DOCX、PDF")
    private String format;

    @Size(max = 200)
    private String title;

    @NotBlank
    @Size(max = 500_000)
    private String content;

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
