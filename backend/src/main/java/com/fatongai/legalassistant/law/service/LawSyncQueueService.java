package com.fatongai.legalassistant.law.service;

import com.fatongai.legalassistant.law.dto.LawSyncJobMessage;
import com.fatongai.legalassistant.law.dto.LawSyncQueuedResult;
import com.fatongai.legalassistant.law.dto.LawSyncRequest;
import com.fatongai.legalassistant.law.dto.LawSyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LawSyncQueueService {
    private static final Logger log = LoggerFactory.getLogger(LawSyncQueueService.class);
    private static final String PROVIDER_NPC_FLK = "npc-flk";
    private static final String PROVIDER_URL = "url";

    private final RabbitTemplate rabbitTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final NpcFlkLawImporter npcFlkLawImporter;
    private final LawCrawlerService lawCrawlerService;
    private final String exchange;
    private final String routingKey;

    public LawSyncQueueService(RabbitTemplate rabbitTemplate,
                               JdbcTemplate jdbcTemplate,
                               NpcFlkLawImporter npcFlkLawImporter,
                               LawCrawlerService lawCrawlerService,
                               @Value("${app.law.sync.rabbit.exchange:legal.law.sync.exchange}") String exchange,
                               @Value("${app.law.sync.rabbit.routing-key:legal.law.sync}") String routingKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.npcFlkLawImporter = npcFlkLawImporter;
        this.lawCrawlerService = lawCrawlerService;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    public LawSyncQueuedResult enqueue(LawSyncRequest rawRequest) {
        LawSyncRequest request = normalizeRequest(rawRequest);
        String provider = resolveProvider(request);
        String taskId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        insertAudit(taskId, provider, "QUEUED", "任务已提交到 RabbitMQ，后台消费者将继续同步。", now);
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, new LawSyncJobMessage(taskId, provider, request, now));
        } catch (RuntimeException e) {
            updateAuditFailed(taskId, e);
            throw e;
        }
        return new LawSyncQueuedResult(true, taskId, provider, "QUEUED", "同步任务已提交后台执行。", now);
    }

    public Map<String, Object> findTask(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return Map.of();
        }
        try {
            return jdbcTemplate.queryForMap("""
                    SELECT task_key taskId, provider, status, fetched_pages fetchedPages,
                           upserted_articles upsertedArticles, skipped_items skippedItems,
                           failed_items failedItems, message, started_at startedAt, finished_at finishedAt
                    FROM law_sync_audit
                    WHERE task_key = ?
                    ORDER BY id DESC
                    LIMIT 1
                    """, taskId.trim());
        } catch (EmptyResultDataAccessException ignored) {
            return Map.of();
        }
    }

    @RabbitListener(queues = "${app.law.sync.rabbit.queue:legal.law.sync.queue}")
    public void consume(LawSyncJobMessage message) {
        if (message == null || !StringUtils.hasText(message.getTaskId())) {
            log.warn("收到空的法规同步消息，已忽略");
            return;
        }
        String taskId = message.getTaskId();
        String provider = StringUtils.hasText(message.getProvider()) ? message.getProvider() : PROVIDER_URL;
        updateAuditStarted(taskId, "RUNNING", "后台同步执行中。");
        try {
            LawSyncRequest request = normalizeRequest(message.getRequest());
            LawSyncResult result;
            if (PROVIDER_NPC_FLK.equalsIgnoreCase(provider)) {
                result = npcFlkLawImporter.importFromApi(request);
            } else {
                List<String> urls = request.getUrls() == null ? List.of() : request.getUrls();
                result = lawCrawlerService.sync(urls, Boolean.TRUE.equals(request.getForceResync()));
            }
            int failed = result.failedUrls() == null ? 0 : result.failedUrls().size();
            String status = result.upsertedArticles() > 0 ? "SUCCESS" : failed > 0 ? "FAILED" : "NO_CHANGE";
            updateAuditFinished(taskId, status, result, "后台同步完成。");
            log.info("法规同步后台任务完成 taskId={} provider={} status={} pages={} upserted={} skipped={} failed={}",
                    taskId, provider, status, result.fetchedPages(), result.upsertedArticles(),
                    result.skippedItems(), result.failedUrls() == null ? 0 : result.failedUrls().size());
        } catch (Exception e) {
            updateAuditFailed(taskId, e);
            log.warn("法规同步后台任务失败 taskId={} provider={}: {}", taskId, provider, e.getMessage(), e);
        }
    }

    private LawSyncRequest normalizeRequest(LawSyncRequest raw) {
        LawSyncRequest request = raw == null ? new LawSyncRequest() : raw;
        if (PROVIDER_NPC_FLK.equalsIgnoreCase(safeTrim(request.getProvider()))) {
            request.setProvider(PROVIDER_NPC_FLK);
            request.setNpcPageStart(defaultInt(request.getNpcPageStart(), 1));
            request.setNpcPageSize(Math.max(1, Math.min(defaultInt(request.getNpcPageSize(), 20), 20)));
            request.setNpcMaxLaws(1);
            if (request.getNpcStreamUntilDetailQuota() == null) {
                request.setNpcStreamUntilDetailQuota(true);
            }
            if (request.getNpcFetchAllListPages() == null) {
                request.setNpcFetchAllListPages(false);
            }
        }
        return request;
    }

    private String resolveProvider(LawSyncRequest request) {
        return PROVIDER_NPC_FLK.equalsIgnoreCase(safeTrim(request.getProvider())) ? PROVIDER_NPC_FLK : PROVIDER_URL;
    }

    private void insertAudit(String taskId, String provider, String status, String message, LocalDateTime now) {
        jdbcTemplate.update("""
                INSERT INTO law_sync_audit(provider, task_key, status, message, started_at)
                VALUES (?, ?, ?, ?, ?)
                """, provider, taskId, status, message, now);
    }

    private void updateAuditStarted(String taskId, String status, String message) {
        jdbcTemplate.update("""
                UPDATE law_sync_audit
                SET status = ?, message = ?
                WHERE task_key = ?
                """, status, message, taskId);
    }

    private void updateAuditFinished(String taskId, String status, LawSyncResult result, String message) {
        int failed = result.failedUrls() == null ? 0 : result.failedUrls().size();
        String detail = failed > 0 ? message + " 失败项：" + String.join("；", result.failedUrls()) : message;
        jdbcTemplate.update("""
                UPDATE law_sync_audit
                SET status = ?, fetched_pages = ?, upserted_articles = ?, skipped_items = ?,
                    failed_items = ?, message = ?, finished_at = ?
                WHERE task_key = ?
                """, status, result.fetchedPages(), result.upsertedArticles(), result.skippedItems(),
                failed, detail, LocalDateTime.now(), taskId);
    }

    private void updateAuditFailed(String taskId, Exception e) {
        String message = e.getClass().getSimpleName() + ": "
                + (StringUtils.hasText(e.getMessage()) ? e.getMessage() : "无错误消息");
        jdbcTemplate.update("""
                UPDATE law_sync_audit
                SET status = ?, failed_items = failed_items + 1, message = ?, finished_at = ?
                WHERE task_key = ?
                """, "FAILED", message, LocalDateTime.now(), taskId);
    }

    private static int defaultInt(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
