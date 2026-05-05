package com.fatongai.legalassistant.law.service;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LawArticleCacheService {
    private static final Logger log = LoggerFactory.getLogger(LawArticleCacheService.class);
    private static final String PREFIX = "law:article:";

    private final RedisTemplate<String, String> redis;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Duration ttl;

    public LawArticleCacheService(RedisTemplate<String, String> redis,
                                  ObjectMapper objectMapper,
                                  @Value("${app.law.cache.enabled:true}") boolean enabled,
                                  @Value("${app.law.cache.ttl-seconds:300}") long ttlSeconds) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.ttl = Duration.ofSeconds(Math.max(10, ttlSeconds));
    }

    public String key(String group, Map<String, ?> params) {
        String json;
        try {
            json = objectMapper.writeValueAsString(params == null ? Map.of() : params);
        } catch (Exception e) {
            json = String.valueOf(params);
        }
        String digest = DigestUtils.md5DigestAsHex(json.getBytes(StandardCharsets.UTF_8));
        return PREFIX + group + ":" + digest;
    }

    public String detailKey(Long id, String interpretationSource) {
        return key("detail", orderedMap("id", id, "interpretationSource", safe(interpretationSource)));
    }

    public String metaKey() {
        return PREFIX + "meta";
    }

    public String tagsKey(String tagType) {
        return key("tags", orderedMap("tagType", safe(tagType)));
    }

    public String versionsKey(Long articleId) {
        return key("versions", orderedMap("articleId", articleId));
    }

    public <T> T get(String key, Class<T> type) {
        if (!enabled || !StringUtils.hasText(key)) {
            return null;
        }
        try {
            String raw = redis.opsForValue().get(key);
            if (!StringUtils.hasText(raw)) {
                return null;
            }
            return objectMapper.readValue(raw, type);
        } catch (Exception e) {
            log.debug("法条缓存读取失败 key={}: {}", key, e.getMessage());
            return null;
        }
    }

    public <T> T get(String key, JavaType type) {
        if (!enabled || !StringUtils.hasText(key)) {
            return null;
        }
        try {
            String raw = redis.opsForValue().get(key);
            if (!StringUtils.hasText(raw)) {
                return null;
            }
            return objectMapper.readValue(raw, type);
        } catch (Exception e) {
            log.debug("法条缓存读取失败 key={}: {}", key, e.getMessage());
            return null;
        }
    }

    public void put(String key, Object value) {
        if (!enabled || !StringUtils.hasText(key) || value == null) {
            return;
        }
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.debug("法条缓存写入失败 key={}: {}", key, e.getMessage());
        }
    }

    public JavaType listType(Class<?> itemType) {
        return objectMapper.getTypeFactory().constructCollectionType(List.class, itemType);
    }

    public void evictAllAfterCommit() {
        if (!enabled) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictAll();
                }
            });
        } else {
            evictAll();
        }
    }

    public void evictAll() {
        if (!enabled) {
            return;
        }
        try {
            List<String> keys = scanKeys(PREFIX + "*");
            if (!keys.isEmpty()) {
                redis.delete(keys);
                log.info("法条缓存已删除 keys={}", keys.size());
            }
        } catch (Exception e) {
            log.warn("法条缓存删除失败: {}", e.getMessage(), e);
        }
    }

    private List<String> scanKeys(String pattern) {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(500).build();
        redis.execute((RedisCallback<Void>) connection -> {
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return null;
        });
        return keys;
    }

    public static Map<String, Object> orderedMap(Object... values) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; values != null && i + 1 < values.length; i += 2) {
            out.put(String.valueOf(values[i]), normalize(values[i + 1]));
        }
        return out;
    }

    private static Object normalize(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(LawArticleCacheService::normalize).toList();
        }
        if (value instanceof String s) {
            return s.trim();
        }
        return value;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
