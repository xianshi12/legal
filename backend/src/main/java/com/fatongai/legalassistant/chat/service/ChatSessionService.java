package com.fatongai.legalassistant.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatongai.legalassistant.chat.dto.ChatSessionView;
import com.fatongai.legalassistant.chat.entity.ChatSession;
import com.fatongai.legalassistant.chat.mapper.ChatSessionMapper;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ChatSessionService {
    private final ChatSessionMapper mapper;
    private final ChatHistoryService chatHistoryService;
    private final ChatFileRegistryService chatFileRegistryService;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public ChatSessionService(ChatSessionMapper mapper,
                              ChatHistoryService chatHistoryService,
                              ChatFileRegistryService chatFileRegistryService,
                              RedisTemplate<String, String> redisTemplate,
                              ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.chatHistoryService = chatHistoryService;
        this.chatFileRegistryService = chatFileRegistryService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public String createSession(Long userId, String title) {
        String finalTitle = (title == null || title.isBlank()) ? "新会话" : title;
        if ("新会话".equals(finalTitle)) {
            return ensureSingleEmptySession(userId);
        }

        String sid = UUID.randomUUID().toString().replace("-", "");
        ChatSession s = new ChatSession();
        s.setSessionId(sid);
        s.setUserId(userId);
        s.setTitle(finalTitle);
        s.setCreatedAt(LocalDateTime.now());
        s.setLastMessageTime(LocalDateTime.now());
        mapper.insert(s);
        cacheSession(userId, s);
        return sid;
    }

    /**
     * 始终以 MySQL 为准列出会话，避免 Redis 中 ZSET/HASH 不一致或残留脏数据时跳过 DB，
     * 导致前端「刷新后最近咨询偶发为空、再刷又有了」的问题。
     */
    public List<ChatSessionView> listSessions(Long userId) {
        String hashKey = sessionHashKey(userId);
        String orderKey = sessionOrderKey(userId);
        redisTemplate.delete(hashKey);
        redisTemplate.delete(orderKey);

        LambdaQueryWrapper<ChatSession> qw = Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getUserId, userId)
                .orderByDesc(ChatSession::getLastMessageTime);
        List<ChatSession> db = mapper.selectList(qw);
        List<ChatSessionView> views = db.stream().map(this::toView).toList();
        List<ChatSessionView> compacted = compactEmptySessions(userId, views);

        List<ChatSession> latest = mapper.selectList(qw);
        for (ChatSession s : latest) {
            cacheSession(userId, s);
        }
        return compacted;
    }

    public void deleteSession(Long userId, String sessionId) {
        LambdaQueryWrapper<ChatSession> qw = Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getSessionId, sessionId);
        mapper.delete(qw);
        evictCache(userId, sessionId);
    }

    public void deleteSessionData(Long userId, String sessionId) {
        deleteSession(userId, sessionId);
        chatHistoryService.clear(sessionId);
        chatFileRegistryService.removeAllForSession(sessionId);
    }

    public void touch(Long userId, String sessionId) {
        LambdaQueryWrapper<ChatSession> qw = Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getSessionId, sessionId);
        ChatSession s = mapper.selectOne(qw);
        if (s == null) {
            s = requireCachedSession(userId, sessionId);
            s.setLastMessageTime(LocalDateTime.now());
            cacheSession(userId, s);
            return;
        }
        s.setLastMessageTime(LocalDateTime.now());
        mapper.updateById(s);
        cacheSession(userId, s);
    }

    public String renameDefaultSession(Long userId, String sessionId, String question, String scenario) {
        ChatSession s = requireSession(userId, sessionId);
        if (!"新会话".equals(s.getTitle())) {
            return s.getTitle();
        }

        String title = buildTitle(question, scenario);
        s.setTitle(title);
        s.setLastMessageTime(LocalDateTime.now());
        if (s.getId() != null) {
            mapper.updateById(s);
        } else {
            LambdaQueryWrapper<ChatSession> qw = Wrappers.<ChatSession>lambdaQuery()
                    .eq(ChatSession::getUserId, userId)
                    .eq(ChatSession::getSessionId, sessionId);
            ChatSession db = mapper.selectOne(qw);
            if (db != null) {
                db.setTitle(title);
                db.setLastMessageTime(s.getLastMessageTime());
                mapper.updateById(db);
                s = db;
            }
        }
        cacheSession(userId, s);
        return title;
    }

    public ChatSession requireSession(Long userId, String sessionId) {
        LambdaQueryWrapper<ChatSession> qw = Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getSessionId, sessionId);
        ChatSession s = mapper.selectOne(qw);
        if (s == null) {
            s = requireCachedSession(userId, sessionId);
        }
        return s;
    }

    private ChatSession requireCachedSession(Long userId, String sessionId) {
        Object raw = redisTemplate.opsForHash().get(sessionHashKey(userId), sessionId);
        if (!(raw instanceof String json) || !StringUtils.hasText(json)) {
            throw new IllegalArgumentException("会话不存在");
        }
        try {
            ChatSessionView v = objectMapper.readValue(json, ChatSessionView.class);
            ChatSession s = new ChatSession();
            s.setSessionId(v.getSessionId());
            s.setUserId(userId);
            s.setTitle(v.getTitle());
            s.setCreatedAt(v.getCreatedAt());
            s.setLastMessageTime(v.getLastMessageTime());
            return s;
        } catch (Exception e) {
            throw new IllegalArgumentException("会话不存在");
        }
    }

    private ChatSessionView toView(ChatSession s) {
        ChatSessionView v = new ChatSessionView();
        v.setSessionId(s.getSessionId());
        v.setTitle(s.getTitle());
        v.setCreatedAt(s.getCreatedAt());
        v.setLastMessageTime(s.getLastMessageTime());
        return v;
    }

    public String ensureSingleEmptySession(Long userId) {
        LambdaQueryWrapper<ChatSession> qw = Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getUserId, userId)
                .orderByDesc(ChatSession::getCreatedAt);
        List<ChatSessionView> sessions = mapper.selectList(qw).stream().map(this::toView).toList();
        List<ChatSessionView> empties = new ArrayList<>();
        for (ChatSessionView s : sessions) {
            if (!"新会话".equals(s.getTitle())) continue;
            if (chatHistoryService.list(s.getSessionId()).isEmpty()) {
                empties.add(s);
            }
        }

        if (empties.isEmpty()) {
            String sid = UUID.randomUUID().toString().replace("-", "");
            ChatSession ns = new ChatSession();
            ns.setSessionId(sid);
            ns.setUserId(userId);
            ns.setTitle("新会话");
            ns.setCreatedAt(LocalDateTime.now());
            ns.setLastMessageTime(LocalDateTime.now());
            mapper.insert(ns);
            cacheSession(userId, ns);
            return sid;
        }

        String keep = empties.get(0).getSessionId(); // list 按 createdAt 倒序，0 为最新
        for (int i = 1; i < empties.size(); i++) {
            String dead = empties.get(i).getSessionId();
            deleteSession(userId, dead);
            chatHistoryService.clear(dead);
            chatFileRegistryService.removeAllForSession(dead);
        }
        // “覆盖”语义：复用最近空会话时也刷新时间，保证前端创建时间/排序实时更新
        ChatSession keepSession = requireSession(userId, keep);
        LocalDateTime now = LocalDateTime.now();
        keepSession.setCreatedAt(now);
        keepSession.setLastMessageTime(now);
        mapper.updateById(keepSession);
        cacheSession(userId, keepSession);
        return keep;
    }

    private List<ChatSessionView> compactEmptySessions(Long userId, List<ChatSessionView> sessions) {
        if (sessions == null || sessions.isEmpty()) return List.of();
        List<ChatSessionView> empties = new ArrayList<>();
        for (ChatSessionView s : sessions) {
            if (!"新会话".equals(s.getTitle())) continue;
            if (chatHistoryService.list(s.getSessionId()).isEmpty()) {
                empties.add(s);
            }
        }
        if (empties.size() <= 1) {
            return sessions;
        }

        String keep = empties.get(0).getSessionId();
        for (int i = 1; i < empties.size(); i++) {
            String dead = empties.get(i).getSessionId();
            deleteSession(userId, dead);
            chatHistoryService.clear(dead);
            chatFileRegistryService.removeAllForSession(dead);
        }
        List<ChatSessionView> out = new ArrayList<>();
        for (ChatSessionView s : sessions) {
            if (!"新会话".equals(s.getTitle())) {
                out.add(s);
                continue;
            }
            if (keep.equals(s.getSessionId())) {
                out.add(s);
            }
        }
        return out;
    }

    private void cacheSession(Long userId, ChatSession s) {
        String hashKey = sessionHashKey(userId);
        String orderKey = sessionOrderKey(userId);
        try {
            String json = objectMapper.writeValueAsString(toView(s));
            redisTemplate.opsForHash().put(hashKey, s.getSessionId(), json);
            double score = toEpochMilli(s.getLastMessageTime() == null ? s.getCreatedAt() : s.getLastMessageTime());
            redisTemplate.opsForZSet().add(orderKey, s.getSessionId(), score);
        } catch (Exception ignored) {
        }
    }

    private void evictCache(Long userId, String sessionId) {
        redisTemplate.opsForHash().delete(sessionHashKey(userId), sessionId);
        redisTemplate.opsForZSet().remove(sessionOrderKey(userId), sessionId);
    }

    private double toEpochMilli(LocalDateTime time) {
        LocalDateTime t = time == null ? LocalDateTime.now() : time;
        return Instant.from(t.atZone(ZoneId.systemDefault())).toEpochMilli();
    }

    private String sessionHashKey(Long userId) {
        return "chat:sessions:meta:" + userId;
    }

    private String sessionOrderKey(Long userId) {
        return "chat:sessions:order:" + userId;
    }

    private String buildTitle(String question, String scenario) {
        String q = question == null ? "" : question.trim()
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s+", " ");
        if (q.isBlank()) {
            return "法律咨询";
        }
        String prefix = StringUtils.hasText(scenario) ? scenario + "：" : "";
        int maxQuestionLength = Math.max(8, 24 - prefix.length());
        String clipped = q.length() <= maxQuestionLength ? q : q.substring(0, maxQuestionLength) + "…";
        return prefix + clipped;
    }
}
