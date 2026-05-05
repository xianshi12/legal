package com.fatongai.legalassistant.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * 登记智能咨询上传文件与会话的对应关系，供历史消息预览下载；会话删除时清理索引与磁盘文件。
 */
@Service
public class ChatFileRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ChatFileRegistryService.class);

    private final RedisTemplate<String, String> redis;
    private final ObjectMapper objectMapper;

    public ChatFileRegistryService(RedisTemplate<String, String> redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void register(String sessionId, String fileId, String absolutePath, String originalName, String contentType) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(fileId) || !StringUtils.hasText(absolutePath)) {
            return;
        }
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("sessionId", sessionId);
            node.put("path", absolutePath);
            node.put("originalName", originalName == null ? "" : originalName);
            node.put("contentType", contentType == null ? "" : contentType);
            redis.opsForValue().set(fileKey(fileId), objectMapper.writeValueAsString(node));
            redis.opsForSet().add(sessionUploadsKey(sessionId), fileId);
        } catch (Exception e) {
            log.warn("[ChatFile] 登记失败 sessionId={} fileId={}: {}", sessionId, fileId, e.getMessage());
        }
    }

    /**
     * @throws IllegalArgumentException 无权限或不存在
     */
    public ResolvedChatFile requireReadable(String fileId, String sessionId) throws IOException {
        if (!StringUtils.hasText(fileId) || !StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("参数无效");
        }
        String raw = redis.opsForValue().get(fileKey(fileId));
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("附件不存在或已过期");
        }
        JsonNode n = objectMapper.readTree(raw);
        String sid = n.path("sessionId").asText("");
        if (!sessionId.equals(sid)) {
            throw new IllegalArgumentException("附件与会话不匹配");
        }
        String path = n.path("path").asText("");
        if (!StringUtils.hasText(path)) {
            throw new IllegalArgumentException("附件路径无效");
        }
        Path p = Path.of(path);
        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException("附件文件已删除");
        }
        return new ResolvedChatFile(
                p,
                n.path("originalName").asText("file"),
                n.path("contentType").asText("application/octet-stream")
        );
    }

    public void removeAllForSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        String setKey = sessionUploadsKey(sessionId);
        Set<String> ids = redis.opsForSet().members(setKey);
        if (ids != null) {
            for (String fileId : ids) {
                try {
                    String raw = redis.opsForValue().get(fileKey(fileId));
                    if (StringUtils.hasText(raw)) {
                        String path = objectMapper.readTree(raw).path("path").asText("");
                        if (StringUtils.hasText(path)) {
                            Files.deleteIfExists(Path.of(path));
                        }
                    }
                } catch (Exception e) {
                    log.warn("[ChatFile] 删除磁盘文件失败 sessionId={} fileId={}: {}", sessionId, fileId, e.getMessage());
                }
                redis.delete(fileKey(fileId));
            }
        }
        redis.delete(setKey);
    }

    private static String fileKey(String fileId) {
        return "chat:file:" + fileId;
    }

    private static String sessionUploadsKey(String sessionId) {
        return "chat:session:" + sessionId + ":uploads";
    }

    public record ResolvedChatFile(Path path, String originalName, String contentType) {
    }
}
