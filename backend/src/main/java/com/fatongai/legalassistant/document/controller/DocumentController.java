package com.fatongai.legalassistant.document.controller;

import com.fatongai.legalassistant.common.ApiResponse;
import com.fatongai.legalassistant.document.dto.DocumentExportRequest;
import com.fatongai.legalassistant.document.dto.DocumentGenerateRequest;
import com.fatongai.legalassistant.document.service.DocumentDraftService;
import com.fatongai.legalassistant.document.service.DocumentExportService;
import com.fatongai.legalassistant.file.FileStorageService;
import com.fatongai.legalassistant.file.TextExtractService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentDraftService documentDraftService;
    private final DocumentExportService documentExportService;
    private final FileStorageService fileStorageService;
    private final TextExtractService textExtractService;

    public DocumentController(DocumentDraftService documentDraftService,
                              DocumentExportService documentExportService,
                              FileStorageService fileStorageService,
                              TextExtractService textExtractService) {
        this.documentDraftService = documentDraftService;
        this.documentExportService = documentExportService;
        this.fileStorageService = fileStorageService;
        this.textExtractService = textExtractService;
    }

    @GetMapping("/templates")
    public ApiResponse<?> templates() {
        return ApiResponse.ok(documentDraftService.templates());
    }

    @PostMapping("/generate")
    public ApiResponse<?> generate(@Valid @RequestBody DocumentGenerateRequest request) {
        return ApiResponse.ok(documentDraftService.generate(request));
    }

    /**
     * 将正文导出为 TXT / DOCX / PDF（二进制下载，非 ApiResponse 包装）。
     */
    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@Valid @RequestBody DocumentExportRequest request) {
        String fmt = request.getFormat() == null ? "" : request.getFormat().trim().toUpperCase(Locale.ROOT);
        try {
            byte[] body = documentExportService.export(fmt, request.getTitle(), request.getContent());
            String base = sanitizeDownloadBase(request.getTitle());
            String ext = fmt.toLowerCase(Locale.ROOT);
            String fullBase = base + "." + ext;
            MediaType ct = switch (fmt) {
                case "TXT" -> MediaType.TEXT_PLAIN;
                case "DOCX" -> MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                case "PDF" -> MediaType.APPLICATION_PDF;
                default -> MediaType.APPLICATION_OCTET_STREAM;
            };
            String ascii = asciiDownloadName(fullBase);
            String encoded = URLEncoder.encode(fullBase, StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .contentType(ct)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded)
                    .body(body);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "导出失败");
        }
    }

    private static String sanitizeDownloadBase(String title) {
        String s = StringUtils.hasText(title) ? title.trim() : "法律文书";
        s = s.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (s.isEmpty()) {
            s = "法律文书";
        }
        if (s.length() > 120) {
            s = s.substring(0, 120);
        }
        return s;
    }

    private static String asciiDownloadName(String name) {
        StringBuilder a = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 32 && c < 127 && c != '"' && c != '\\') {
                a.append(c);
            } else {
                a.append('_');
            }
        }
        String out = a.toString().trim();
        return out.isEmpty() ? "export.bin" : out;
    }

    @PostMapping(value = "/optimize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<?> optimize(@RequestParam("documentType") String documentType,
                                   @RequestParam(value = "outputMode", defaultValue = "optimize") String outputMode,
                                   @RequestParam(value = "facts", required = false) String facts,
                                   @RequestParam(value = "extraRequirements", required = false) String extraRequirements,
                                   @RequestPart(value = "file", required = false) MultipartFile file,
                                   @RequestParam(value = "rawText", required = false) String rawText) throws Exception {
        String uploadedText = rawText;
        if (file != null && !file.isEmpty()) {
            var stored = fileStorageService.store(file);
            try (FileInputStream in = new FileInputStream(stored.path())) {
                uploadedText = textExtractService.extract(in);
            }
        }
        if (!StringUtils.hasText(uploadedText)) {
            throw new IllegalArgumentException("请上传已有文书或粘贴文书正文");
        }
        return ApiResponse.ok(documentDraftService.optimize(documentType, outputMode, facts, extraRequirements, uploadedText));
    }
}
