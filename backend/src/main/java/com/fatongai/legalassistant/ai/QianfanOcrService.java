package com.fatongai.legalassistant.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能咨询附件图片 OCR：百度千帆 OpenAI 兼容 chat/completions，模型 deepseek-ocr。
 * <p>
 * 千帆 OCR 容易触发 RPM 限流，本服务做三件事：
 * 1. 全进程串行调用；
 * 2. 两次真实请求之间保持最小间隔；
 * 3. 遇到 429 时等待一个限流窗口后自动重试。
 */
@Service
public class QianfanOcrService {

    private static final Logger log = LoggerFactory.getLogger(QianfanOcrService.class);
    private static final Object RPM_LOCK = new Object();
    private static volatile long lastOcrEndNanos = 0L;

    private final ObjectMapper objectMapper;
    private final Map<String, CachedOcr> ocrResultCache = new ConcurrentHashMap<>();

    @Value("${app.ai.ocr.api-key:}")
    private String apiKey;

    @Value("${app.ai.ocr.chat-completions-url:https://qianfan.baidubce.com/v2/chat/completions}")
    private String chatCompletionsUrl;

    @Value("${app.ai.ocr.model:deepseek-ocr}")
    private String ocrModel;

    @Value("${app.ai.ocr.user-prompt:OCR this image.}")
    private String userPrompt;

    @Value("${app.ai.ocr.max-image-edge-pixels:2048}")
    private int maxImageEdgePixels;

    @Value("${app.ai.ocr.recompress-min-bytes:320000}")
    private int recompressMinBytes;

    @Value("${app.ai.ocr.min-interval-ms:7000}")
    private long minIntervalMs;

    @Value("${app.ai.ocr.rate-limit-retry-delay-ms:65000}")
    private long rateLimitRetryDelayMs;

    @Value("${app.ai.ocr.rate-limit-max-retries:1}")
    private int rateLimitMaxRetries;

    @Value("${app.ai.ocr.result-cache-ttl-ms:600000}")
    private long resultCacheTtlMs;

    @Value("${app.ai.ocr.result-cache-max-entries:128}")
    private int resultCacheMaxEntries;

    public QianfanOcrService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String ocrImage(byte[] imageBytes, String mimeType) throws Exception {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("未配置图片识别密钥（QIANFAN_OCR_API_KEY / app.ai.ocr.api-key）");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            return "";
        }
        String mt = StringUtils.hasText(mimeType) ? mimeType : "application/octet-stream";
        if (!mt.startsWith("image/")) {
            throw new IllegalArgumentException("仅支持图片类型的 OCR");
        }

        String endpoint = StringUtils.hasText(chatCompletionsUrl)
                ? chatCompletionsUrl.trim()
                : "https://qianfan.baidubce.com/v2/chat/completions";
        PreparedImage prepared = prepareImageForRequest(imageBytes, mt);
        String digest = sha256Hex(prepared.bytes());
        CachedOcr cached = ocrResultCache.get(digest);
        if (cached != null && cached.isValid()) {
            log.info("[OCR] 命中结果缓存 digest={}… textLen={}", digest.substring(0, 8), cached.text().length());
            return cached.text();
        }

        String payload = buildRequestJson(prepared);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofMinutes(6))
                .header("Authorization", "Bearer " + apiKey.trim())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(45)).build();

        long t0 = System.currentTimeMillis();
        log.info("[OCR] 千帆 DeepSeek-OCR 请求准备 mime={}, bytes={} (原始 {}), model={}, endpoint={}",
                prepared.mimeType(), prepared.bytes().length, imageBytes.length, ocrModel, endpoint);

        HttpResponse<String> response;
        synchronized (RPM_LOCK) {
            cached = ocrResultCache.get(digest);
            if (cached != null && cached.isValid()) {
                return cached.text();
            }
            response = sendWithPacingAndRetry(client, request, endpoint);
        }

        long elapsed = System.currentTimeMillis() - t0;
        String rawBody = response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = friendlyHttpError(response.statusCode(), rawBody);
            log.warn("[OCR] HTTP 失败 status={}, durationMs={}, body={}", response.statusCode(), elapsed, clip(rawBody, 1200));
            throw new IOException(message);
        }

        JsonNode tree = objectMapper.readTree(rawBody);
        JsonNode err = tree.get("error");
        if (err != null && !err.isNull()) {
            String msg = err.path("message").asText(err.path("msg").asText(err.toString()));
            throw new IOException("千帆 OCR 接口错误：" + msg + extractRequestIdSuffix(rawBody));
        }

        JsonNode choices = tree.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new IOException("千帆返回无 choices" + extractRequestIdSuffix(rawBody));
        }
        String text = extractMessageText(choices.get(0).path("message")).trim();
        if (StringUtils.hasText(text)) {
            putResultCache(digest, text);
            log.info("[OCR] 成功 durationMs={}, textLen={}", elapsed, text.length());
        } else {
            log.warn("[OCR] 成功但正文为空 durationMs={}", elapsed);
        }
        return text;
    }

    private HttpResponse<String> sendWithPacingAndRetry(HttpClient client, HttpRequest request, String endpoint) throws IOException {
        int maxRetry = Math.max(0, rateLimitMaxRetries);
        for (int attempt = 0; attempt <= maxRetry; attempt++) {
            paceBeforeNextCall();
            HttpResponse<String> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("图片识别请求被中断", e);
            } finally {
                lastOcrEndNanos = System.nanoTime();
            }
            if (response.statusCode() != 429 || attempt >= maxRetry) {
                return response;
            }
            long delay = Math.max(10_000L, rateLimitRetryDelayMs);
            log.warn("[OCR] 千帆 RPM 限流，{} ms 后第 {} 次重试，endpoint={}, body={}",
                    delay, attempt + 1, endpoint, clip(response.body(), 500));
            sleep(delay);
        }
        throw new IOException("图片识别失败：限流重试异常");
    }

    private void paceBeforeNextCall() {
        long gap = Math.max(0L, minIntervalMs);
        if (gap <= 0 || lastOcrEndNanos == 0L) return;
        long elapsedMs = (System.nanoTime() - lastOcrEndNanos) / 1_000_000L;
        if (elapsedMs < gap) {
            long wait = gap - elapsedMs;
            log.info("[OCR] 全局节流：等待 {} ms（min-interval-ms={}）", wait, gap);
            sleep(wait);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String buildRequestJson(PreparedImage prepared) throws IOException {
        String b64 = Base64.getEncoder().encodeToString(prepared.bytes());
        String dataUrl = "data:" + prepared.mimeType() + ";base64," + b64;

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", StringUtils.hasText(ocrModel) ? ocrModel : "deepseek-ocr");

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        ArrayNode content = objectMapper.createArrayNode();

        ObjectNode textPart = objectMapper.createObjectNode();
        textPart.put("type", "text");
        textPart.put("text", StringUtils.hasText(userPrompt) ? userPrompt.trim() : "OCR this image.");
        content.add(textPart);

        ObjectNode imgPart = objectMapper.createObjectNode();
        imgPart.put("type", "image_url");
        ObjectNode imageUrl = objectMapper.createObjectNode();
        imageUrl.put("url", dataUrl);
        imgPart.set("image_url", imageUrl);
        content.add(imgPart);

        userMsg.set("content", content);
        messages.add(userMsg);
        root.set("messages", messages);
        return objectMapper.writeValueAsString(root);
    }

    private String friendlyHttpError(int statusCode, String rawBody) {
        String suffix = extractRequestIdSuffix(rawBody);
        if (statusCode == 429) {
            return "图片识别请求过于频繁，已触发千帆 RPM 限流。请等待约 1 分钟后重试，或减少一次上传的图片数量。" + suffix;
        }
        return "图片识别失败 HTTP " + statusCode + "：" + clip(rawBody, 800) + suffix;
    }

    private PreparedImage prepareImageForRequest(byte[] imageBytes, String mimeType) {
        int cap = Math.max(256, maxImageEdgePixels);
        int heavy = Math.max(50_000, recompressMinBytes);
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (src == null) return new PreparedImage(imageBytes, mimeType);
            int w = src.getWidth();
            int h = src.getHeight();
            int maxSide = Math.max(w, h);
            if (maxSide <= cap && imageBytes.length <= heavy) {
                return new PreparedImage(imageBytes, mimeType);
            }

            int nw = w;
            int nh = h;
            if (maxSide > cap) {
                double scale = (double) cap / maxSide;
                nw = Math.max(1, (int) Math.round(w * scale));
                nh = Math.max(1, (int) Math.round(h * scale));
            }

            BufferedImage rgb = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, nw, nh);
                g.drawImage(src, 0, 0, nw, nh, null);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(rgb, "jpg", bos);
            return new PreparedImage(bos.toByteArray(), "image/jpeg");
        } catch (Exception e) {
            log.warn("[OCR] 图片预处理异常，使用原图: {}", e.toString());
            return new PreparedImage(imageBytes, mimeType);
        }
    }

    private void putResultCache(String digest, String text) {
        if (ocrResultCache.size() >= Math.max(16, resultCacheMaxEntries)) {
            ocrResultCache.clear();
        }
        ocrResultCache.put(digest, new CachedOcr(text, System.currentTimeMillis() + Math.max(60_000L, resultCacheTtlMs)));
    }

    private static String extractMessageText(JsonNode message) {
        if (message == null || message.isMissingNode()) return "";
        String content = nodeToPlainText(message.get("content"));
        if (StringUtils.hasText(content)) return content;
        return nodeToPlainText(message.get("reasoning_content"));
    }

    private static String nodeToPlainText(JsonNode content) {
        if (content == null || content.isNull()) return "";
        if (content.isTextual()) return content.asText("");
        if (!content.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : content) {
            String type = part.path("type").asText("");
            if ("text".equals(type)) {
                sb.append(part.path("text").asText(""));
            }
        }
        return sb.toString();
    }

    private String extractRequestIdSuffix(String rawBody) {
        try {
            JsonNode tree = objectMapper.readTree(rawBody);
            if (tree.hasNonNull("id")) return " id=" + tree.get("id").asText();
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            return "nohash-" + Integer.toHexString(java.util.Arrays.hashCode(data));
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private record PreparedImage(byte[] bytes, String mimeType) {
    }

    private record CachedOcr(String text, long expiresAtMillis) {
        boolean isValid() {
            return StringUtils.hasText(text) && System.currentTimeMillis() < expiresAtMillis;
        }
    }
}
