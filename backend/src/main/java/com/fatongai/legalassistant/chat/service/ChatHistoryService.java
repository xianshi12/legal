package com.fatongai.legalassistant.chat.service;

import com.fatongai.legalassistant.chat.dto.ChatMessage;
import com.fatongai.legalassistant.chat.dto.ChatMessageAttachment;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ChatHistoryService {
    private final RedisTemplate<String, String> redis;
    private final ObjectMapper objectMapper;

    @Value("${app.chat.history-max:200}")
    private int historyMax;

    public ChatHistoryService(RedisTemplate<String, String> redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void clear(String sessionId) {
        redis.delete(key(sessionId));
    }

    public List<ChatMessage> list(String sessionId) {
        List<String> raw = redis.opsForList().range(key(sessionId), 0, historyMax - 1);
        if (raw == null) return List.of();
        List<ChatMessage> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            try {
                out.add(objectMapper.readValue(s, ChatMessage.class));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public boolean isEmpty(String sessionId) {
        Long size = redis.opsForList().size(key(sessionId));
        return size == null || size == 0;
    }

    public List<ChatMessage> listChronological(String sessionId, int maxCount) {
        int end = Math.max(0, maxCount - 1);
        List<String> raw = redis.opsForList().range(key(sessionId), 0, end);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            try {
                out.add(objectMapper.readValue(s, ChatMessage.class));
            } catch (Exception ignored) {
            }
        }
        // Redis 里是 leftPush（最新在前），给模型时改为时间顺序（最旧在前）
        out.sort((a, b) -> {
            if (a.getCreatedAt() == null && b.getCreatedAt() == null) return 0;
            if (a.getCreatedAt() == null) return -1;
            if (b.getCreatedAt() == null) return 1;
            return a.getCreatedAt().compareTo(b.getCreatedAt());
        });
        return out;
    }

    public ChatMessage appendUser(String sessionId, String content) {
        return appendUser(sessionId, content, List.of());
    }

    public ChatMessage appendUser(String sessionId, String content, List<ChatMessageAttachment> attachments) {
        ChatMessage m = new ChatMessage();
        m.setId(UUID.randomUUID().toString().replace("-", ""));
        m.setRole("user");
        m.setContent(content);
        m.setAttachments(attachments == null ? List.of() : attachments);
        m.setCreatedAt(LocalDateTime.now());
        push(sessionId, m);
        return m;
    }

    public ChatMessage appendAssistant(String sessionId,
                                      String content,
                                      List<String> references,
                                      String scenario,
                                      String conclusion,
                                      List<String> legalBasis,
                                      List<String> actionSteps,
                                      List<String> evidenceChecklist,
                                      List<String> riskWarnings,
                                      List<String> followupQuestions) {
        ChatMessage m = new ChatMessage();
        m.setId(UUID.randomUUID().toString().replace("-", ""));
        m.setRole("assistant");
        m.setContent(content);
        m.setReferences(references);
        m.setScenario(scenario);
        m.setConclusion(conclusion);
        m.setLegalBasis(legalBasis);
        m.setActionSteps(actionSteps);
        m.setEvidenceChecklist(evidenceChecklist);
        m.setRiskWarnings(riskWarnings);
        m.setFollowupQuestions(followupQuestions);
        m.setCreatedAt(LocalDateTime.now());
        push(sessionId, m);
        return m;
    }

    private void push(String sessionId, ChatMessage m) {
        try {
            String json = objectMapper.writeValueAsString(m);
            redis.opsForList().leftPush(key(sessionId), json);
            redis.opsForList().trim(key(sessionId), 0, historyMax - 1);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private String key(String sessionId) {
        return "chat:session:" + sessionId;
    }
}
