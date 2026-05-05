package com.fatongai.legalassistant.law.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class QwenEmbeddingService {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public QwenEmbeddingService(ObjectMapper objectMapper,
                                @Value("${app.ai.embedding.api-key:}") String apiKey,
                                @Value("${app.ai.embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
                                @Value("${app.ai.embedding.model:text-embedding-v4}") String model) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.baseUrl = trimSlash(baseUrl);
        this.model = model;
        this.restClient = RestClient.builder().build();
    }

    public boolean isEnabled() {
        return StringUtils.hasText(apiKey);
    }

    public String model() {
        return model;
    }

    public List<Double> embed(String text) {
        if (!isEnabled() || !StringUtils.hasText(text)) {
            return List.of();
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", text,
                    "dimensions", 1024
            );
            Map<String, Object> response = restClient.post()
                    .uri(baseUrl + "/embeddings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(body)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
            Object dataObj = response == null ? null : response.get("data");
            if (!(dataObj instanceof List<?> data) || data.isEmpty()) {
                return List.of();
            }
            Object firstObj = data.get(0);
            if (!(firstObj instanceof Map<?, ?> first)) {
                return List.of();
            }
            Object embeddingObj = first.get("embedding");
            if (!(embeddingObj instanceof List<?> embedding)) {
                return List.of();
            }
            List<Double> vector = new ArrayList<>(embedding.size());
            for (Object value : embedding) {
                if (value instanceof Number n) {
                    vector.add(n.doubleValue());
                }
            }
            return vector;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public String toJson(List<Double> vector) {
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (Exception e) {
            return "[]";
        }
    }

    public List<Double> fromJson(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    public double cosine(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        int len = Math.min(a.size(), b.size());
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < len; i++) {
            double x = a.get(i);
            double y = b.get(i);
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private String trimSlash(String url) {
        if (url == null) return "";
        String t = url.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
