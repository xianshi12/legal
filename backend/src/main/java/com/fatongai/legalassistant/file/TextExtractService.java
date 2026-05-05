package com.fatongai.legalassistant.file;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
public class TextExtractService {
    private static final Logger log = LoggerFactory.getLogger(TextExtractService.class);
    private final Tika tika = new Tika();

    /**
     * @param label 日志用标识，例如原始文件名
     */
    public String extract(InputStream in, String label) throws IOException {
        String safeLabel = label == null || label.isBlank() ? "(stream)" : label;
        long t0 = System.nanoTime();
        log.info("[contract-scan] 开始解析文件: {}", safeLabel);
        try {
            String text = tika.parseToString(in);
            if (text == null) text = "";
            text = text.trim();
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            log.info("[contract-scan] 解析完成: {}，字符数 {}，耗时 {} ms", safeLabel, text.length(), ms);
            if (text.isEmpty()) {
                log.warn("[contract-scan] 解析结果为空: {}", safeLabel);
            }
            return text;
        } catch (TikaException e) {
            log.warn("[contract-scan] Tika 解析异常: {} — {}", safeLabel, e.getMessage());
            throw new IOException("文件解析失败：" + e.getMessage(), e);
        }
    }

    public String extract(InputStream in) throws IOException {
        return extract(in, null);
    }
}

