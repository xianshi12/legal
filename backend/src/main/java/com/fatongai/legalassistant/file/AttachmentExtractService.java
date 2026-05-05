package com.fatongai.legalassistant.file;

import com.fatongai.legalassistant.ai.QianfanOcrService;
import com.fatongai.legalassistant.chat.dto.AttachmentExtractItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class AttachmentExtractService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentExtractService.class);

    private final TextExtractService textExtractService;
    private final QianfanOcrService qianfanOcrService;

    public AttachmentExtractService(TextExtractService textExtractService,
                                    QianfanOcrService qianfanOcrService) {
        this.textExtractService = textExtractService;
        this.qianfanOcrService = qianfanOcrService;
    }

    /**
     * 上传阶段的即时解析（不落盘）
     */
    public AttachmentExtractItem extractMultipart(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        long size = file.getSize();
        String ct = file.getContentType();
        log.info("[附件提取] 开始 file={}, size={}, contentType={}", name, size, ct);
        try {
            if (ct != null && ct.startsWith("image/")) {
                String text = qianfanOcrService.ocrImage(file.getBytes(), ct);
                log.info("[附件提取] 完成(图片OCR) file={}, ok=true, textLen={}", name, nvl(text).length());
                return new AttachmentExtractItem(name, nvl(text), true, null);
            }
            try (InputStream in = file.getInputStream()) {
                String text = textExtractService.extract(in);
                log.info("[附件提取] 完成(文档解析) file={}, ok=true, textLen={}", name, nvl(text).length());
                return new AttachmentExtractItem(name, nvl(text), true, null);
            }
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (isRateLimit(msg)) {
                log.warn("[附件提取] OCR 限流 file={}, contentType={}, cause={}", name, ct, msg);
            } else {
                log.warn("[附件提取] 失败 file={}, contentType={}, cause={}", name, ct, msg, e);
            }
            return new AttachmentExtractItem(name, "", false, msg);
        }
    }

    /**
     * 对话发送阶段：从已落盘文件解析（与 Multipart 第二次上传对齐）
     */
    public AttachmentExtractItem extractFromStored(FileStorageService.StoredFile stored) {
        String name = stored.originalName();
        Path path = Path.of(stored.path());
        String ct = stored.contentType();
        log.info("[附件提取] 开始(已落盘) file={}, path={}, contentType={}", name, path, ct);
        try {
            if (ct != null && ct.startsWith("image/")) {
                byte[] bytes = Files.readAllBytes(path);
                String text = qianfanOcrService.ocrImage(bytes, ct);
                log.info("[附件提取] 完成(图片OCR/落盘) file={}, ok=true, textLen={}", name, nvl(text).length());
                return new AttachmentExtractItem(name, nvl(text), true, null);
            }
            try (InputStream in = Files.newInputStream(path)) {
                String text = textExtractService.extract(in);
                log.info("[附件提取] 完成(文档解析/落盘) file={}, ok=true, textLen={}", name, nvl(text).length());
                return new AttachmentExtractItem(name, nvl(text), true, null);
            }
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (isRateLimit(msg)) {
                log.warn("[附件提取] OCR 限流(落盘) file={}, contentType={}, cause={}", name, ct, msg);
            } else {
                log.warn("[附件提取] 失败(落盘) file={}, contentType={}, cause={}", name, ct, msg, e);
            }
            return new AttachmentExtractItem(name, "", false, msg);
        }
    }

    private static String nvl(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean isRateLimit(String msg) {
        if (msg == null) return false;
        return msg.contains("RPM 限流") || msg.contains("429") || msg.contains("rate_limit");
    }
}
