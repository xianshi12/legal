package com.fatongai.legalassistant.law.controller;

import com.fatongai.legalassistant.common.ApiResponse;
import com.fatongai.legalassistant.law.dto.LawSearchRequest;
import com.fatongai.legalassistant.law.dto.LawSyncRequest;
import com.fatongai.legalassistant.law.service.LawArticleService;
import com.fatongai.legalassistant.law.service.LawSyncQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/laws")
public class LawArticleController {
    /** 与 POST /maintenance/delete-seed-articles 的 confirm 参数一致，防止误删。 */
    public static final String DELETE_SEED_CONFIRM = "DELETE_BUILTIN_LAW_ARTICLES";

    private static final Logger log = LoggerFactory.getLogger(LawArticleController.class);
    private final LawArticleService lawArticleService;
    private final LawSyncQueueService lawSyncQueueService;

    public LawArticleController(LawArticleService lawArticleService,
                                LawSyncQueueService lawSyncQueueService) {
        this.lawArticleService = lawArticleService;
        this.lawSyncQueueService = lawSyncQueueService;
    }

    @GetMapping("/search")
    public ApiResponse<?> search(@RequestParam(value = "q", required = false) String query,
                                 @RequestParam(value = "searchType", defaultValue = "fullText") String searchType,
                                 @RequestParam(value = "categories", required = false) List<String> categories,
                                 @RequestParam(value = "statuses", required = false) List<String> statuses,
                                 @RequestParam(value = "issuingBodies", required = false) List<String> issuingBodies,
                                 @RequestParam(value = "region", required = false) String region,
                                 @RequestParam(value = "sort", defaultValue = "relevance") String sort,
                                 @RequestParam(value = "matchMode", defaultValue = "fuzzy") String matchMode,
                                 @RequestParam(value = "keywordMode", defaultValue = "any") String keywordMode,
                                 @RequestParam(value = "level", required = false) String level,
                                 @RequestParam(value = "lawName", required = false) String lawName,
                                 @RequestParam(value = "advLawTitle", required = false) String advLawTitle,
                                 @RequestParam(value = "publishDateFrom", required = false) String publishDateFromRaw,
                                 @RequestParam(value = "publishDateTo", required = false) String publishDateToRaw,
                                 @RequestParam(value = "effectiveDateFrom", required = false) String effectiveDateFromRaw,
                                 @RequestParam(value = "effectiveDateTo", required = false) String effectiveDateToRaw,
                                 @RequestParam(value = "includeHistory", defaultValue = "false") Boolean includeHistory,
                                 @RequestParam(value = "page", defaultValue = "1") Integer page,
                                 @RequestParam(value = "size", defaultValue = "10") Integer size) {
        long t0 = System.nanoTime();
        LocalDate publishFrom = parseAdvDate(publishDateFromRaw);
        LocalDate publishTo = parseAdvDate(publishDateToRaw);
        LocalDate effectiveFrom = parseAdvDate(effectiveDateFromRaw);
        LocalDate effectiveTo = parseAdvDate(effectiveDateToRaw);
        var body = lawArticleService.search(query, searchType, categories, statuses, issuingBodies, region, sort,
                matchMode, level, lawName, keywordMode, advLawTitle,
                publishFrom, publishTo, effectiveFrom, effectiveTo,
                includeHistory,
                page, size);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (log.isDebugEnabled()) {
            log.debug("法条检索 q={} type={} page={} size={} total={} 耗时={}ms",
                    query, searchType, page, size, body.total(), ms);
        } else if (ms > 2000) {
            log.warn("法条检索较慢 q={} type={} total={} 耗时={}ms", query, searchType, body.total(), ms);
        }
        return ApiResponse.ok(body);
    }

    @PostMapping("/search")
    public ApiResponse<?> searchPost(@RequestBody(required = false) LawSearchRequest request) {
        LawSearchRequest r = request == null ? new LawSearchRequest() : request;
        var body = lawArticleService.search(
                r.getQ(),
                r.getSearchType(),
                r.getCategories(),
                r.getStatuses(),
                r.getIssuingBodies(),
                r.getRegion(),
                r.getSort(),
                r.getMatchMode(),
                r.getLevel(),
                r.getLawName(),
                r.getKeywordMode(),
                r.getAdvLawTitle(),
                parseAdvDate(r.getPublishDateFrom()),
                parseAdvDate(r.getPublishDateTo()),
                parseAdvDate(r.getEffectiveDateFrom()),
                parseAdvDate(r.getEffectiveDateTo()),
                r.getIncludeHistory(),
                r.getPage(),
                r.getSize());
        return ApiResponse.ok(body);
    }

    @GetMapping("/{id}")
    public ApiResponse<?> detail(@PathVariable Long id,
                                 @RequestParam(value = "interpretationSource", defaultValue = "auto") String interpretationSource) {
        return ApiResponse.ok(lawArticleService.detail(id, interpretationSource));
    }

    @GetMapping("/meta")
    public ApiResponse<?> meta() {
        return ApiResponse.ok(lawArticleService.meta());
    }

    @GetMapping("/tags")
    public ApiResponse<?> tags(@RequestParam(value = "tagType", required = false) String tagType) {
        return ApiResponse.ok(lawArticleService.tagTree(tagType));
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<?> versions(@PathVariable Long id) {
        return ApiResponse.ok(lawArticleService.versionHistory(id));
    }

    @PostMapping("/maintenance/reindex")
    public ApiResponse<?> reindex(@RequestParam(value = "limit", defaultValue = "500") Integer limit) {
        int changed = lawArticleService.rebuildLightIndex(limit == null ? 500 : limit);
        return ApiResponse.ok(Map.of("reindexed", changed));
    }

    /**
     * 删除内置 / 离线示例法条（{@code seed://} 或来源名「内置基础库」）。不设鉴权，请勿对公网暴露后端。
     */
    @PostMapping("/maintenance/delete-seed-articles")
    public ApiResponse<?> deleteSeedArticles(@RequestParam("confirm") String confirm) {
        if (!DELETE_SEED_CONFIRM.equals(confirm)) {
            return ApiResponse.fail("confirm 参数必须为: " + DELETE_SEED_CONFIRM);
        }
        int n = lawArticleService.deleteSeedAndBuiltinArticles();
        log.warn("maintenance/delete-seed-articles 已删除内置法条 {} 条", n);
        return ApiResponse.ok(Map.of("deleted", n));
    }

    @PostMapping("/sync")
    public ApiResponse<?> sync(@RequestBody(required = false) LawSyncRequest request) {
        if (request != null && StringUtils.hasText(request.getProvider())
                && "npc-flk".equalsIgnoreCase(request.getProvider().trim())) {
            log.info("法条同步请求 NPC provider=npc-flk page={}-{} size={} maxLaws={} keyword={}",
                    request.getNpcPageStart(), request.getNpcPageEnd(), request.getNpcPageSize(),
                    request.getNpcMaxLaws(), request.getNpcSearchContent());
            return ApiResponse.ok(lawSyncQueueService.enqueue(request));
        }
        List<String> urls = request == null || request.getUrls() == null ? List.of() : request.getUrls();
        boolean forceResync = request != null && Boolean.TRUE.equals(request.getForceResync());
        log.info("法条同步请求 URL 抓取 urls={} forceResync={}", urls.size(), forceResync);
        return ApiResponse.ok(lawSyncQueueService.enqueue(request));
    }

    @GetMapping("/sync/{taskId}")
    public ApiResponse<?> syncTask(@PathVariable String taskId) {
        return ApiResponse.ok(lawSyncQueueService.findTask(taskId));
    }

    private static LocalDate parseAdvDate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String s = raw.trim();
        if (s.length() >= 10) {
            s = s.substring(0, 10);
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
