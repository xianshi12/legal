package com.fatongai.legalassistant.law.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatongai.legalassistant.law.dto.LawSyncRequest;
import com.fatongai.legalassistant.law.dto.LawSyncResult;
import com.fatongai.legalassistant.law.entity.LawArticle;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 国家法律法规数据库同步器。
 *
 * <p>新逻辑只保留一条清晰链路：从 {@code https://flk.npc.gov.cn/search} 对应的公开列表接口取候选，
 * 按 {@code bbbs} 判断本地是否已有，已有跳过且不占本次额度；未入库的法规再抓详情，解析详情 JSON
 * 中的正文树，按「第...条」拆为本地法条记录。</p>
 */
@Service
public class NpcFlkLawImporter {
    private static final Logger log = LoggerFactory.getLogger(NpcFlkLawImporter.class);
    /** 防止多路 HTTP / 定时任务并发同步导致唯一键冲突与死锁。 */
    private static final ReentrantLock NPC_API_IMPORT_LOCK = new ReentrantLock();
    private static final String BASE_URL = "https://flk.npc.gov.cn";
    private static final String SEARCH_PATH = "/search";
    public static final String NPC_PUBLIC_SEARCH_URL = BASE_URL + SEARCH_PATH;

    private static final String SEARCH_LIST_PATH = "/law-search/search/list";
    private static final String DETAIL_PATH = "/law-search/search/flfgDetails";
    /** 允许「第 一 条」「第1条」等与官网 HTML 转写一致的条号形态 */
    private static final Pattern ARTICLE_PATTERN = Pattern.compile(
            "(第\\s*[一二三四五六七八九十百千万零〇两0-9]+\\s*条(?:之一|之二|之三|之四|之五)?)");
    private static final Set<String> TEXT_KEYS = Set.of(
            "content", "text", "txt", "html", "body", "nr", "zw"
    );
    private static final Set<String> NOISE_KEYS = Set.of(
            "bbbs", "id", "code", "codeId", "flfgCodeId", "pcodeId", "sxx", "sort", "path", "url", "href",
            "gbrq", "sxrq", "createdAt", "updatedAt", "publishDate", "effectiveDate"
    );
    /**
     * 国家法律法规数据库中「法律」模块的常用叶子分类。120=民法商法，150=社会法（劳动、社保等）。
     */
    private static final List<Integer> DEFAULT_FOCUS_FLFG_CODES = List.of(120, 150);

    private final LawArticleService lawArticleService;
    private final ObjectMapper objectMapper;
    private final Tika tika;
    private final RestClient flkClient;
    private final HttpClient documentClient;
    private final boolean scheduledEnabled;
    private final int pageSize;
    private final int maxLawsPerRun;
    private final int httpRetries;
    private final int httpRetryDelayMs;
    private final int detailDelayMs;
    private final int detailDelayJitterMs;
    private final boolean skipIfPresent;
    private final String listSortField;
    private final String listSortOrder;
    private final LocalRegulationsPolicy localRegulationsPolicyDefault;
    /** 详情页 HTML 补抓超时（与 RestClient 读超时一致量级） */
    private final int flkReadTimeoutMs;

    public NpcFlkLawImporter(@Lazy LawArticleService lawArticleService,
                             ObjectMapper objectMapper,
                             @Value("${app.law.npc-flk.scheduled-enabled:false}") boolean scheduledEnabled,
                             @Value("${app.law.npc-flk.connect-timeout-ms:20000}") int connectTimeoutMs,
                             @Value("${app.law.npc-flk.read-timeout-ms:120000}") int readTimeoutMs,
                             @Value("${app.law.npc-flk.http-retries:3}") int httpRetries,
                             @Value("${app.law.npc-flk.http-retry-delay-ms:1500}") int httpRetryDelayMs,
                             @Value("${app.law.npc-flk.page-size:20}") int pageSize,
                             @Value("${app.law.npc-flk.max-laws-per-run:1}") int maxLawsPerRun,
                             @Value("${app.law.npc-flk.detail-delay-ms:800}") int detailDelayMs,
                             @Value("${app.law.npc-flk.detail-delay-jitter-ms:300}") int detailDelayJitterMs,
                             @Value("${app.law.npc-flk.list-sort-field:gbrq}") String listSortField,
                             @Value("${app.law.npc-flk.list-sort-order:-1}") String listSortOrder,
                             @Value("${app.law.npc-flk.skip-if-present:true}") boolean skipIfPresent,
                             @Value("${app.law.npc-flk.local-regulations-policy:skip}") String localRegulationsPolicyRaw) {
        this.lawArticleService = lawArticleService;
        this.objectMapper = objectMapper;
        this.tika = new Tika();
        this.scheduledEnabled = scheduledEnabled;
        this.httpRetries = Math.max(1, Math.min(httpRetries, 6));
        this.httpRetryDelayMs = Math.max(0, httpRetryDelayMs);
        this.pageSize = Math.max(1, Math.min(pageSize, 20));
        this.maxLawsPerRun = Math.max(1, Math.min(maxLawsPerRun, 20));
        this.detailDelayMs = Math.max(0, detailDelayMs);
        this.detailDelayJitterMs = Math.max(0, Math.min(detailDelayJitterMs, 3000));
        this.listSortField = StringUtils.hasText(listSortField) ? listSortField.trim() : "gbrq";
        this.listSortOrder = StringUtils.hasText(listSortOrder) ? listSortOrder.trim() : "-1";
        this.skipIfPresent = skipIfPresent;
        this.localRegulationsPolicyDefault = LocalRegulationsPolicy.parse(localRegulationsPolicyRaw);
        this.flkReadTimeoutMs = Math.max(5000, readTimeoutMs);
        this.documentClient = insecureDocumentClient(this.flkReadTimeoutMs);

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(1000, connectTimeoutMs));
        requestFactory.setReadTimeout(Math.max(5000, readTimeoutMs));
        this.flkClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory(requestFactory)
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .defaultHeader("Accept", "application/json, text/plain, */*")
                .defaultHeader("Accept-Language", "zh-CN,zh;q=0.9")
                .defaultHeader("Referer", NPC_PUBLIC_SEARCH_URL)
                .defaultHeader("Origin", BASE_URL)
                .build();
        ensureCookieManager();
        log.info("NPC法规同步器已初始化 pageSize={} maxLaws={} retries={} skipIfPresent={} localPolicy={} sort={}:{}",
                this.pageSize, this.maxLawsPerRun, this.httpRetries, this.skipIfPresent,
                this.localRegulationsPolicyDefault, this.listSortField, this.listSortOrder);
    }

    @Scheduled(cron = "${app.law.npc-flk.cron:0 0 4 * * ?}")
    public void scheduledNpcSync() {
        if (!scheduledEnabled) {
            return;
        }
        LawSyncRequest req = new LawSyncRequest();
        req.setProvider("npc-flk");
        req.setNpcPageStart(1);
        req.setNpcPageSize(pageSize);
        req.setNpcMaxLaws(maxLawsPerRun);
        importFromApi(req);
    }

    public LawSyncResult importFromApi(LawSyncRequest request) {
        NPC_API_IMPORT_LOCK.lock();
        try {
            return importFromApiLocked(request);
        } finally {
            NPC_API_IMPORT_LOCK.unlock();
        }
    }

    private LawSyncResult importFromApiLocked(LawSyncRequest request) {
        LawSyncRequest req = request == null ? new LawSyncRequest() : request;
        int startPage = Math.max(1, Optional.ofNullable(req.getNpcPageStart()).orElse(1));
        int size = Math.max(1, Math.min(Optional.ofNullable(req.getNpcPageSize()).orElse(pageSize), 20));
        int targetNewLaws = Math.max(1, Math.min(Optional.ofNullable(req.getNpcMaxLaws()).orElse(maxLawsPerRun), 20));
        int searchRange = normalizeSearchRange(req);
        int searchType = normalizeSearchType(req);
        String keyword = StringUtils.hasText(req.getNpcSearchContent()) ? req.getNpcSearchContent().trim() : "";
        List<Integer> statusCodes = req.getNpcStatusCodes() == null || req.getNpcStatusCodes().isEmpty()
                ? List.of(3)
                : req.getNpcStatusCodes();
        List<Integer> flfgCodes = resolveFlfgCodes(req);
        LocalRegulationsPolicy localPolicy = StringUtils.hasText(req.getNpcLocalRegulationsPolicy())
                ? LocalRegulationsPolicy.parse(req.getNpcLocalRegulationsPolicy())
                : localRegulationsPolicyDefault;
        boolean doSkip = skipIfPresent && !Boolean.TRUE.equals(req.getForceResync());
        boolean streamUntilQuota = Boolean.TRUE.equals(req.getNpcStreamUntilDetailQuota())
                || req.getNpcPageEnd() == null
                || Boolean.TRUE.equals(req.getNpcFetchAllListPages());
        Integer fixedEndPage = streamUntilQuota ? null : Math.max(startPage, req.getNpcPageEnd());

        int fetchedPages = 0;
        int upserted = 0;
        int skipped = 0;
        int detailAttempts = 0;
        int importedLaws = 0;
        int maxDetailAttempts = Math.max(targetNewLaws, Math.min(20, targetNewLaws * 10));
        List<String> failed = new ArrayList<>();
        long t0 = System.nanoTime();

        tryWarmup(failed);
        int page = startPage;
        while (importedLaws < targetNewLaws && detailAttempts < maxDetailAttempts && page <= startPage + 200) {
            if (fixedEndPage != null && page > fixedEndPage) {
                break;
            }
            List<NpcRow> rows;
            try {
                rows = fetchRows(keyword, searchRange, searchType, statusCodes, flfgCodes, page, size);
                fetchedPages++;
            } catch (Exception e) {
                failed.add("列表第 " + page + " 页抓取失败：" + summary(e));
                log.warn("NPC列表抓取失败 page={}: {}", page, summary(e), e);
                break;
            }
            if (rows.isEmpty()) {
                break;
            }
            rows = rows.stream()
                    .filter(row -> localPolicy != LocalRegulationsPolicy.SKIP || !isLocalRow(row))
                    .toList();
            Set<String> present = doSkip ? presentBbbs(rows) : Set.of();
            for (NpcRow row : rows) {
                if (importedLaws >= targetNewLaws || detailAttempts >= maxDetailAttempts) {
                    break;
                }
                if (doSkip && present.contains(row.bbbs())) {
                    int removed = lawArticleService.deleteNpcFullTextPlaceholders(row.bbbs(), "");
                    if (removed > 0) {
                        log.info("NPC法规已按条入库，清理旧全文占位后跳过 bbbs={} title={} removed={}",
                                row.bbbs(), row.title(), removed);
                    }
                    skipped++;
                    log.info("NPC法规已存在，跳过 bbbs={} title={}", row.bbbs(), row.title());
                    continue;
                }
                try {
                    NpcDetail detail = fetchDetail(row);
                    List<LawArticle> articles = toArticles(row, detail, keyword);
                    if (articles.isEmpty()) {
                        failed.add("《" + row.title() + "》未解析到正文");
                    } else {
                        if (articles.size() > 1) {
                            int removed = lawArticleService.deleteNpcFullTextPlaceholders(row.bbbs(), detail.detailUrl());
                            if (removed > 0) {
                                log.info("NPC法规已拆分为按条记录，清理旧全文占位 bbbs={} title={} removed={}",
                                        row.bbbs(), row.title(), removed);
                            }
                        }
                        int changed = upsertAllWithDeadlockRetry(articles);
                        upserted += changed;
                        if (changed > 0) {
                            importedLaws++;
                        }
                        log.info("NPC法规入库完成 bbbs={} title={} articles={} changed={}",
                                row.bbbs(), row.title(), articles.size(), changed);
                    }
                } catch (Exception e) {
                    failed.add("《" + row.title() + "》：" + summary(e));
                    log.warn("NPC详情处理失败 bbbs={} title={}: {}", row.bbbs(), row.title(), summary(e), e);
                }
                detailAttempts++;
                sleepBetweenDetails(failed);
            }
            if (rows.size() < size || fixedEndPage != null && page >= fixedEndPage) {
                break;
            }
            page++;
        }
        int embedded = lawArticleService.updateMissingEmbeddings(Math.max(20, upserted));
        log.info("NPC同步结束 pages={} detailAttempts={} importedLaws={} skipped={} upserted={} embedded={} failed={} costMs={}",
                fetchedPages, detailAttempts, importedLaws, skipped, upserted, embedded, failed.size(),
                (System.nanoTime() - t0) / 1_000_000L);
        return new LawSyncResult(fetchedPages, upserted, embedded, skipped, failed, LocalDateTime.now());
    }

    /**
     * 死锁时 InnoDB 会回滚整批事务，必须在「整次 upsertAll」粒度重试，不能只对单条重试。
     */
    private int upsertAllWithDeadlockRetry(List<LawArticle> articles) {
        final int maxAttempts = 4;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return lawArticleService.upsertAll(articles);
            } catch (DeadlockLoserDataAccessException e) {
                if (attempt >= maxAttempts) {
                    throw e;
                }
                log.warn("NPC 法条批量写入死锁，整批重试 attempt={}/{} articles={}", attempt, maxAttempts - 1, articles.size(), e);
                try {
                    Thread.sleep(50L * attempt + ThreadLocalRandom.current().nextInt(120));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ie);
                }
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private List<NpcRow> fetchRows(String keyword,
                                   int searchRange,
                                   int searchType,
                                   List<Integer> statusCodes,
                                   List<Integer> flfgCodes,
                                   int page,
                                   int size) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("searchRange", searchRange);
        body.put("searchType", searchType);
        body.put("xgzlSearch", false);
        body.put("searchContent", keyword);
        body.put("pageNum", page);
        body.put("pageSize", size);
        body.set("sxrq", objectMapper.createArrayNode());
        body.set("gbrq", objectMapper.createArrayNode());
        body.set("gbrqYear", objectMapper.createArrayNode());
        body.set("zdjgCodeId", objectMapper.createArrayNode());
        body.set("sxx", intArray(statusCodes));
        body.set("flfgCodeId", intArray(flfgCodes));
        ObjectNode order = objectMapper.createObjectNode();
        order.put("order", listSortOrder);
        order.put("sort", listSortField);
        body.set("orderByParam", order);

        JsonNode root = postJson(SEARCH_LIST_PATH, body);
        assertOk(root, "列表接口返回异常");
        JsonNode rowsNode = firstArray(root.path("rows"), root.path("data").path("rows"),
                root.path("data").path("records"), root.path("data").path("list"));
        if (rowsNode == null) {
            return List.of();
        }
        Map<String, NpcRow> out = new LinkedHashMap<>();
        for (JsonNode item : rowsNode) {
            NpcRow row = parseRow(item);
            if (StringUtils.hasText(row.bbbs())) {
                out.putIfAbsent(row.bbbs(), row);
            }
        }
        return new ArrayList<>(out.values());
    }

    private NpcDetail fetchDetail(NpcRow row) throws Exception {
        JsonNode root = getJson(DETAIL_PATH + "?bbbs=" + urlEncode(row.bbbs()));
        assertOk(root, "详情接口返回异常");
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new IOException("详情接口缺少 data");
        }
        String title = firstNonBlank(
                htmlText(data.path("title").asText("")),
                row.title()
        );
        String detailUrl = toFlkDetailUrl(row.bbbs());
        String plainText = resolveDetailPlainTextForSplitting(data, detailUrl);
        if (!StringUtils.hasText(plainText)) {
            throw new IOException("详情 JSON 中未解析到正文");
        }
        return new NpcDetail(title, detailUrl, data, plainText);
    }

    /**
     * 供 {@link LawArticleExternalBodyResolver} 按 {@code npc:bbbs} 映射拉取合并正文（不入库）。
     */
    public String fetchNpcMergedPlainText(String bbbs) {
        if (!StringUtils.hasText(bbbs)) {
            return "";
        }
        try {
            JsonNode root = getJson(DETAIL_PATH + "?bbbs=" + urlEncode(bbbs.trim()));
            assertOk(root, "详情接口返回异常");
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                return "";
            }
            String title = firstNonBlank(htmlText(data.path("title").asText("")), "");
            String detailUrl = toFlkDetailUrl(bbbs.trim());
            return resolveDetailPlainTextForSplitting(data, detailUrl);
        } catch (Exception e) {
            log.warn("NPC 合并正文获取失败 bbbs={}: {}", bbbs, e.getMessage());
            return "";
        }
    }

    private List<LawArticle> toArticles(NpcRow row, NpcDetail detail, String keyword) {
        String lawName = cleanLawName(detail.title());
        List<LawArticle> articles = splitArticles(lawName, detail.plainText(), detail.detailUrl());
        LocalDate publishDate = parseDate(firstNonBlank(detail.data().path("gbrq").asText(""), row.publishDate()));
        LocalDate effectiveDate = parseDate(firstNonBlank(detail.data().path("sxrq").asText(""), row.effectiveDate()));
        String level = firstNonBlank(detail.data().path("flxz").asText(""), row.level(), "法律法规");
        String issuing = firstNonBlank(
                detail.data().path("zdjgMc").asText(""),
                detail.data().path("zdjg").asText(""),
                detail.data().path("zdjjg").asText("")
        );
        Integer statusCode = detail.data().hasNonNull("sxx") ? detail.data().path("sxx").asInt() : row.statusCode();

        String npcRef = "npc:" + row.bbbs();
        for (LawArticle article : articles) {
            String sliceForInfer = article.getContent();
            article.setArticleSummary(LawArticleBodyExtractor.clamp(
                    LawArticleDigestHelper.stripTechnicalNoise(sliceForInfer), 480));
            article.setInterpretationSummary(LawArticleBodyExtractor.clamp(
                    LawArticleDigestHelper.colloquialInterpretation(article.getLawName(), article.getArticleNo(), sliceForInfer), 500));
            article.setInterpretation(null);
            article.setVectorJson(null);
            article.setVectorModel(null);
            article.setExternalRef(npcRef);
            article.setContentRef(npcRef);
            article.setCategory(inferCategory(article.getLawName(), sliceForInfer, level));
            article.setLevel(level);
            article.setIssuingBody(htmlText(issuing));
            article.setRegion("全国");
            article.setStatus(mapStatus(statusCode));
            article.setSourceName(LawArticleService.SOURCE_NPC_FLK);
            article.setSourceUrl(detail.detailUrl());
            article.setPublishDate(publishDate);
            article.setEffectiveDate(effectiveDate);
            article.setSourceUpdatedAt(LocalDateTime.now());
            article.setScenarios(LawArticleDigestHelper.inferScenarios(article.getLawName(), sliceForInfer));
            article.setExceptionsText(LawArticleDigestHelper.inferExceptionsText(article.getLawName(), sliceForInfer));
            article.setCauseOfAction(LawArticleDigestHelper.inferCauseOfAction(article.getLawName(), sliceForInfer));
        }
        return articles;
    }

    private List<LawArticle> splitArticles(String lawName, String text, String sourceUrl) {
        String normalized = normalizePlainText(text);
        normalized = LawArticleBodyExtractor.trimLeadingBeforeFirstArticleHeading(normalized);
        Matcher matcher = ARTICLE_PATTERN.matcher(normalized);
        List<Integer> starts = new ArrayList<>();
        List<String> articleNos = new ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start());
            articleNos.add(matcher.group(1));
        }
        List<LawArticle> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (starts.isEmpty()) {
            return out;
        }
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1) : normalized.length();
            String content = normalized.substring(start, end).trim();
            String no = normalizeArticleNoToken(articleNos.get(i));
            if (content.length() < 16 || looksLikeTocLine(content)) {
                continue;
            }
            addArticle(out, seen, lawName, no, content, sourceUrl);
        }
        return out;
    }

    private void addArticle(List<LawArticle> out, Set<String> seen, String lawName, String articleNo, String content, String sourceUrl) {
        String key = lawName + "\n" + articleNo + "\n" + content;
        if (!seen.add(key)) {
            return;
        }
        LawArticle article = new LawArticle();
        article.setLawName(lawName);
        article.setArticleNo(articleNo);
        article.setTitle("《" + lawName + "》" + articleNo);
        article.setContent(LawArticleDigestHelper.stripTechnicalNoise(content));
        article.setSourceUrl(sourceUrl);
        out.add(article);
    }

    /**
     * 从详情 JSON 与整树长文本中择优：避免把「第一条第二条…」式目录当作唯一正文入库。
     */
    private String extractDetailPlainText(JsonNode data) {
        String structuredArticles = extractArticleBlocks(data);
        if (StringUtils.hasText(structuredArticles)
                && !LawArticleDigestHelper.looksLikeSparseArticleHeadingList(structuredArticles)) {
            return structuredArticles;
        }

        List<String> preferred = new ArrayList<>();
        collectStructuredText(data.path("content"), preferred);
        collectStructuredText(data.path("contents"), preferred);
        collectStructuredText(data.path("body"), preferred);
        collectStructuredText(data.path("text"), preferred);
        String joinedPreferred = normalizePlainText(String.join("\n", preferred));

        List<String> longChunks = new ArrayList<>();
        collectLongStrings(data, longChunks, "");
        String longestChunk = "";
        for (String raw : longChunks) {
            String t = normalizePlainText(htmlText(raw));
            if (t.length() > longestChunk.length()) {
                longestChunk = t;
            }
        }

        String bestNonCatalog = "";
        for (String cand : List.of(joinedPreferred, longestChunk)) {
            if (!StringUtils.hasText(cand)) {
                continue;
            }
            if (!LawArticleDigestHelper.looksLikeSparseArticleHeadingList(cand) && cand.length() > bestNonCatalog.length()) {
                bestNonCatalog = cand;
            }
        }
        if (bestNonCatalog.length() >= 200) {
            return bestNonCatalog;
        }
        if (bestNonCatalog.length() >= 60) {
            return bestNonCatalog;
        }
        if (StringUtils.hasText(joinedPreferred) && joinedPreferred.length() >= 60) {
            return joinedPreferred;
        }
        if (longestChunk.length() >= 60) {
            return longestChunk;
        }
        List<String> fallback = new ArrayList<>();
        collectLongStrings(data, fallback, "");
        return normalizePlainText(String.join("\n", fallback));
    }

    private String extractArticleBlocks(JsonNode data) {
        List<String> blocks = new ArrayList<>();
        collectArticleBlocks(data, blocks);
        if (blocks.isEmpty()) {
            return "";
        }
        String joined = normalizePlainText(String.join("\n", blocks));
        return hasSubstantiveArticleText(joined) ? joined : "";
    }

    private void collectArticleBlocks(JsonNode node, List<String> out) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectArticleBlocks(child, out);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }

        // 部分法规详情 JSON 的 content 树仅在 title 上挂「第一条 ……」整段正文，无独立 content/text 字段
        String longTitle = normalizePlainText(htmlText(node.path("title").asText("")));
        if (longTitle.length() >= 36) {
            Matcher tm = ARTICLE_PATTERN.matcher(longTitle);
            if (tm.find() && tm.start() <= 6 && hasSubstantiveArticleText(longTitle)) {
                out.add(longTitle);
                return;
            }
        }

        String heading = firstArticleHeadingInNode(node);
        String body = firstArticleBodyInNode(node);
        if (StringUtils.hasText(heading) && hasSubstantiveArticleText(body)) {
            String cleanBody = normalizePlainText(htmlText(body));
            if (!cleanBody.startsWith(heading.replaceAll("\\s+", "")) && !cleanBody.startsWith(heading)) {
                out.add(heading.replaceAll("\\s+", "") + "\n" + cleanBody);
            } else {
                out.add(cleanBody);
            }
            return;
        }

        node.fields().forEachRemaining(e -> collectArticleBlocks(e.getValue(), out));
    }

    private String firstArticleHeadingInNode(JsonNode node) {
        for (String key : List.of("title", "subTitle", "label", "name", "value")) {
            String v = htmlText(node.path(key).asText(""));
            Matcher m = ARTICLE_PATTERN.matcher(v);
            if (m.find() && v.replaceAll("\\s+", "").length() <= 18) {
                return m.group(1);
            }
        }
        return "";
    }

    private String firstArticleBodyInNode(JsonNode node) {
        List<String> candidates = new ArrayList<>();
        for (String key : List.of("content", "text", "txt", "html", "body", "nr", "zw")) {
            JsonNode value = node.path(key);
            if (value.isTextual()) {
                String text = htmlText(value.asText(""));
                if (hasSubstantiveArticleText(text)) {
                    candidates.add(text);
                }
            } else if (value.isArray() || value.isObject()) {
                List<String> nested = new ArrayList<>();
                collectBodyOnlyText(value, nested);
                String joined = normalizePlainText(String.join("\n", nested));
                if (hasSubstantiveArticleText(joined)) {
                    candidates.add(joined);
                }
            }
        }
        for (String key : List.of("children", "childrens", "list", "items")) {
            List<String> nested = new ArrayList<>();
            collectBodyOnlyText(node.path(key), nested);
            String joined = normalizePlainText(String.join("\n", nested));
            if (hasSubstantiveArticleText(joined)) {
                candidates.add(joined);
            }
        }
        return candidates.stream()
                .max((a, b) -> Integer.compare(a.length(), b.length()))
                .orElse("");
    }

    private void collectBodyOnlyText(JsonNode node, List<String> into) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            String text = htmlText(node.asText(""));
            if (hasSubstantiveArticleText(text)) {
                into.add(text);
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectBodyOnlyText(child, into);
            }
            return;
        }
        if (node.isObject()) {
            for (String key : List.of("content", "text", "txt", "html", "body", "nr", "zw")) {
                collectBodyOnlyText(node.path(key), into);
            }
            collectBodyOnlyText(node.path("children"), into);
            collectBodyOnlyText(node.path("childrens"), into);
            collectBodyOnlyText(node.path("list"), into);
            collectBodyOnlyText(node.path("items"), into);
        }
    }

    private boolean hasSubstantiveArticleText(String text) {
        String s = LawArticleDigestHelper.stripTechnicalNoise(htmlText(text));
        if (!StringUtils.hasText(s) || s.length() < 12) {
            return false;
        }
        String withoutHeadings = ARTICLE_PATTERN.matcher(s).replaceAll("");
        String substantive = withoutHeadings.replaceAll("[\\s　、，,。；;：:（）()《》0-9一二三四五六七八九十百千万零〇两章节编条款项目的附则]+", "");
        return substantive.length() >= 8
                || s.contains("为了")
                || s.contains("适用")
                || s.contains("应当")
                || s.contains("不得")
                || s.contains("可以")
                || s.contains("依照");
    }

    /**
     * 若 JSON 正文仍像条号目录，则补抓官网详情页 HTML 转纯文本，尽量得到可「按条」拆分的实质正文。
     */
    private String resolveDetailPlainTextForSplitting(JsonNode data, String detailUrl) {
        String plain = extractDetailPlainText(data);
        if (hasArticleHeading(plain) && !LawArticleDigestHelper.looksLikeSparseArticleHeadingList(plain)) {
            return plain;
        }
        String fromOfficialFile = fetchOfficialDocumentPlain(data);
        if (hasArticleHeading(fromOfficialFile)
                && !LawArticleDigestHelper.looksLikeSparseArticleHeadingList(fromOfficialFile)) {
            return fromOfficialFile;
        }
        if (StringUtils.hasText(fromOfficialFile)
                && !LawArticleDigestHelper.looksLikeSparseArticleHeadingList(fromOfficialFile)
                && fromOfficialFile.length() >= 400) {
            return fromOfficialFile;
        }
        if (StringUtils.hasText(fromOfficialFile) && fromOfficialFile.length() >= 1200) {
            return fromOfficialFile;
        }
        String fromHtml = fetchFlkDetailHtmlPlain(detailUrl);
        if (hasArticleHeading(fromHtml) && !LawArticleDigestHelper.looksLikeSparseArticleHeadingList(fromHtml)) {
            return fromHtml;
        }
        String fromReferencedPage = fetchReferencedHtmlPlain(data, detailUrl);
        if (hasArticleHeading(fromReferencedPage)
                && !LawArticleDigestHelper.looksLikeSparseArticleHeadingList(fromReferencedPage)) {
            return fromReferencedPage;
        }
        if (StringUtils.hasText(fromHtml)
                && !LawArticleDigestHelper.looksLikeSparseArticleHeadingList(fromHtml)
                && fromHtml.length() >= Math.min(400, plain.length() + 80)) {
            log.debug("NPC 详情 JSON 疑似目录，已用详情页 HTML 补抓正文 bbbsUrl={}", detailUrl);
            return fromHtml;
        }
        if (StringUtils.hasText(fromHtml) && fromHtml.length() > plain.length() * 2) {
            return fromHtml;
        }
        return plain;
    }

    private String fetchReferencedHtmlPlain(JsonNode data, String detailUrl) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        collectCandidateUrls(data, urls);
        urls.removeIf(url -> safe(url).equals(detailUrl));
        for (String url : urls) {
            String plain = fetchExternalHtmlPlain(url);
            if (hasArticleHeading(plain) && !LawArticleDigestHelper.looksLikeSparseArticleHeadingList(plain)) {
                log.debug("NPC JSON 引用页正文解析成功 url={} chars={}", url, plain.length());
                return plain;
            }
        }
        return "";
    }

    private void collectCandidateUrls(JsonNode node, Set<String> out) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            String raw = htmlText(node.asText(""));
            if (!StringUtils.hasText(raw)) {
                return;
            }
            Matcher m = Pattern.compile("https?://[^\\s\"'<>，。；、）)]+").matcher(raw);
            while (m.find()) {
                addCandidateUrl(m.group(), out);
            }
            addCandidateUrl(raw, out);
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectCandidateUrls(child, out);
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> collectCandidateUrls(e.getValue(), out));
        }
    }

    private static void addCandidateUrl(String raw, Set<String> out) {
        if (!StringUtils.hasText(raw)) {
            return;
        }
        String url = raw.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return;
        }
        String lower = url.toLowerCase();
        if (!(lower.contains("npc.gov.cn") || lower.contains("flk.npc.gov.cn"))) {
            return;
        }
        if (lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".pdf") || lower.endsWith(".ofd")) {
            return;
        }
        out.add(url);
    }

    private String fetchExternalHtmlPlain(String url) {
        if (!StringUtils.hasText(url) || !url.startsWith("http")) {
            return "";
        }
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                    .timeout(flkReadTimeoutMs)
                    .followRedirects(true)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Referer", NPC_PUBLIC_SEARCH_URL)
                    .get();
            return extractHtmlMainPlain(doc);
        } catch (Exception e) {
            log.debug("NPC JSON 引用页补抓失败 url={}: {}", url, e.getMessage());
            return "";
        }
    }

    private String fetchOfficialDocumentPlain(JsonNode data) {
        JsonNode oss = data == null ? null : data.path("ossFile");
        if (oss == null || oss.isMissingNode() || oss.isNull()) {
            return "";
        }
        // PDF 线性正文往往比 Word 更适合按「条」拆分；先 PDF 再 Word，避免 Tika 对 docx 抽出不完整短文本后提前放弃
        for (String path : List.of(
                oss.path("ossPdfPath").asText(""),
                oss.path("ossWordPath").asText("")
        )) {
            String url = toOfficialDocumentUrl(path);
            if (!StringUtils.hasText(url)) {
                continue;
            }
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMillis(flkReadTimeoutMs))
                        .header("Accept", "*/*")
                        .header("Accept-Language", "zh-CN,zh;q=0.9")
                        .header("Referer", NPC_PUBLIC_SEARCH_URL)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                        .GET()
                        .build();
                HttpResponse<byte[]> resp = documentClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                    log.debug("NPC 官方附件下载非成功状态 url={} status={}", url, resp.statusCode());
                    continue;
                }
                byte[] bytes = resp.body();
                if (bytes == null || bytes.length == 0) {
                    continue;
                }
                String parsed = normalizePlainText(parseOfficialDocument(bytes, url));
                if (!StringUtils.hasText(parsed)) {
                    continue;
                }
                if (hasArticleHeading(parsed)) {
                    log.debug("NPC 官方附件正文解析成功 url={} chars={}", url, parsed.length());
                    return parsed;
                }
                // 条号与「第」之间为全角空格、零宽符等时 hasArticleHeading 可能为 false，仍应作为正文源
                if (parsed.length() >= 400
                        && !LawArticleDigestHelper.looksLikeSparseArticleHeadingList(parsed)) {
                    log.debug("NPC 官方附件正文解析成功（长文本兜底） url={} chars={}", url, parsed.length());
                    return parsed;
                }
                if (parsed.length() >= 1200) {
                    log.debug("NPC 官方附件正文解析成功（超长兜底） url={} chars={}", url, parsed.length());
                    return parsed;
                }
            } catch (Throwable e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.debug("NPC 官方附件正文解析失败 url={}: {}", url, e.getMessage());
            }
        }
        return "";
    }

    private String parseOfficialDocument(byte[] bytes, String url) throws Exception {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String lower = safe(url).toLowerCase();
        if (lower.endsWith(".pdf")) {
            try (PDDocument doc = Loader.loadPDF(bytes)) {
                return normalizePlainText(new PDFTextStripper().getText(doc));
            }
        }
        return normalizePlainText(tika.parseToString(new ByteArrayInputStream(bytes)));
    }

    private static HttpClient insecureDocumentClient(int timeoutMs) {
        try {
            TrustManager[] trustAll = new TrustManager[] {
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(Math.max(5000, timeoutMs)))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .sslContext(context)
                    .build();
        } catch (Exception e) {
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(Math.max(5000, timeoutMs)))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
    }

    private static String toOfficialDocumentUrl(String rawPath) {
        if (!StringUtils.hasText(rawPath)) {
            return "";
        }
        String path = rawPath.trim();
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        if (!StringUtils.hasText(fileName)) {
            return "";
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".docx") || lower.endsWith(".doc")) {
            return "https://wb.flk.npc.gov.cn/flfg/WORD/" + urlEncode(fileName);
        }
        if (lower.endsWith(".pdf")) {
            return "https://wb.flk.npc.gov.cn/flfg/PDF/" + urlEncode(fileName);
        }
        return "";
    }

    private static String toFlkDetailUrl(String bbbs) {
        String id = safe(bbbs).trim();
        if (!StringUtils.hasText(id)) {
            return BASE_URL + "/search";
        }
        String encoded = Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.UTF_8));
        return BASE_URL + "/detail2.html?" + encoded;
    }

    private static boolean hasArticleHeading(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String n = normalizePlainText(text);
        return StringUtils.hasText(n) && ARTICLE_PATTERN.matcher(n).find();
    }

    private String fetchFlkDetailHtmlPlain(String detailUrl) {
        if (!StringUtils.hasText(detailUrl) || !detailUrl.startsWith("http")) {
            return "";
        }
        try {
            Document doc = Jsoup.connect(detailUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                    .timeout(flkReadTimeoutMs)
                    .followRedirects(true)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Referer", NPC_PUBLIC_SEARCH_URL)
                    .get();
            return extractHtmlMainPlain(doc);
        } catch (Exception e) {
            log.debug("NPC 详情页 HTML 补抓失败 url={}: {}", detailUrl, e.getMessage());
            return "";
        }
    }

    private static String extractHtmlMainPlain(Document doc) {
        String[] selectors = {"article", "main", ".detail-content", ".content", ".article-content", ".law-content", "#content", "body"};
        for (String sel : selectors) {
            Element el = doc.selectFirst(sel);
            if (el != null && StringUtils.hasText(el.text())) {
                return el.text().trim();
            }
        }
        return "";
    }

    private void collectStructuredText(JsonNode node, List<String> into) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            addReadableText(into, node.asText());
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectStructuredText(child, into);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        for (String key : TEXT_KEYS) {
            JsonNode value = node.path(key);
            if (value.isTextual()) {
                addReadableText(into, value.asText());
            }
        }
        collectStructuredText(node.path("children"), into);
        collectStructuredText(node.path("childrens"), into);
        collectStructuredText(node.path("list"), into);
        collectStructuredText(node.path("items"), into);
    }

    private void collectLongStrings(JsonNode node, List<String> into, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            String raw = node.asText("");
            if (!NOISE_KEYS.contains(fieldName) && htmlText(raw).length() >= 20 && !looksLikeMetadata(raw)) {
                addReadableText(into, raw);
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectLongStrings(child, into, fieldName);
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> collectLongStrings(e.getValue(), into, e.getKey()));
        }
    }

    private void addReadableText(List<String> into, String raw) {
        String text = htmlText(raw);
        if (!StringUtils.hasText(text) || looksLikeMetadata(text)) {
            return;
        }
        into.add(text);
    }

    private Set<String> presentBbbs(List<NpcRow> rows) {
        Set<String> candidates = new HashSet<>();
        for (NpcRow row : rows) {
            if (StringUtils.hasText(row.bbbs())) {
                candidates.add(row.bbbs());
            }
        }
        return lawArticleService.findNpcBbbsWithSplitArticlesPresent(candidates);
    }

    private List<Integer> resolveFlfgCodes(LawSyncRequest req) {
        LinkedHashSet<Integer> codes = new LinkedHashSet<>();
        addCategoryCodes(req.getNpcType(), codes);
        if (req.getNpcCategoryLabels() != null) {
            for (String label : req.getNpcCategoryLabels()) {
                addCategoryCodes(label, codes);
            }
        }
        if (codes.isEmpty()) {
            codes.addAll(DEFAULT_FOCUS_FLFG_CODES);
        }
        return new ArrayList<>(codes);
    }

    private void addCategoryCodes(String raw, Set<Integer> codes) {
        if (!StringUtils.hasText(raw)) {
            return;
        }
        for (String part : raw.split("[,，;；、\\s]+")) {
            String s = part.trim();
            if (!StringUtils.hasText(s)) {
                continue;
            }
            try {
                codes.add(Integer.parseInt(s));
                continue;
            } catch (NumberFormatException ignored) {
            }
            if (s.contains("民法") || s.contains("商法") || s.contains("合同") || s.contains("借贷")) {
                codes.add(120);
            } else if (s.contains("劳动") || s.contains("社会法") || s.contains("社保") || s.contains("工伤")) {
                codes.add(150);
            } else if (s.contains("行政法")) {
                codes.add(130);
            } else if (s.contains("经济法")) {
                codes.add(140);
            } else if (s.contains("刑法")) {
                codes.add(160);
            } else if (s.contains("诉讼") || s.contains("程序")) {
                codes.add(170);
            } else if (s.contains("生态") || s.contains("环境")) {
                codes.add(155);
            } else if (s.contains("综合") || s.contains("法律")) {
                codes.addAll(DEFAULT_FOCUS_FLFG_CODES);
            }
        }
    }

    private JsonNode postJson(String path, ObjectNode body) throws Exception {
        String raw = runWithRetry("POST " + path, () -> flkClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN)
                .body(body.toString())
                .retrieve()
                .body(String.class));
        return parseJson(path, raw);
    }

    private JsonNode getJson(String pathAndQuery) throws Exception {
        String raw = runWithRetry("GET " + pathAndQuery, () -> flkClient.get()
                .uri(pathAndQuery)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN)
                .retrieve()
                .body(String.class));
        return parseJson(pathAndQuery, raw);
    }

    private JsonNode parseJson(String step, String raw) throws IOException {
        if (!StringUtils.hasText(raw)) {
            throw new IOException(step + " 返回空内容");
        }
        String t = raw.trim();
        if (t.startsWith("<")) {
            throw new IOException(step + " 返回 HTML 而非 JSON，可能被站点反爬/挑战页拦截");
        }
        return objectMapper.readTree(t);
    }

    private <T> T runWithRetry(String step, IoSupplier<T> supplier) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= httpRetries; attempt++) {
            try {
                return supplier.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                last = e;
                if (attempt >= httpRetries || !isRetriable(e)) {
                    throw e;
                }
                log.warn("{} 第{}次失败，{}ms 后重试：{}", step, attempt, httpRetryDelayMs, summary(e));
                if (httpRetryDelayMs > 0) {
                    Thread.sleep(httpRetryDelayMs);
                }
            }
        }
        throw last == null ? new IOException("重试逻辑异常") : last;
    }

    private void tryWarmup(List<String> failed) {
        try {
            runWithRetry("GET " + SEARCH_PATH, () -> flkClient.get()
                    .uri(SEARCH_PATH)
                    .accept(MediaType.TEXT_HTML, MediaType.TEXT_PLAIN)
                    .retrieve()
                    .body(String.class));
        } catch (Exception e) {
            failed.add("检索入口预热失败：" + summary(e));
            log.debug("NPC检索入口预热失败：{}", summary(e));
        }
    }

    private static void assertOk(JsonNode root, String message) throws IOException {
        int code = root.path("code").asInt(200);
        if (code != 200 && code != 0) {
            throw new IOException(root.path("msg").asText(message));
        }
    }

    private static JsonNode firstArray(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.isArray()) {
                return node;
            }
        }
        return null;
    }

    private NpcRow parseRow(JsonNode row) {
        Integer flfgCodeId = row.hasNonNull("flfgCodeId") ? row.path("flfgCodeId").asInt() : null;
        Integer statusCode = row.hasNonNull("sxx") ? row.path("sxx").asInt() : null;
        return new NpcRow(
                row.path("bbbs").asText(""),
                htmlText(firstNonBlank(row.path("title").asText(""), row.path("flfgmc").asText(""))),
                row.path("flxz").asText(""),
                row.path("gbrq").asText(""),
                row.path("sxrq").asText(""),
                statusCode,
                flfgCodeId
        );
    }

    private static boolean isLocalRow(NpcRow row) {
        String text = safe(row.level()) + row.flfgCodeId();
        return text.contains("地方") || text.contains("自治条例") || text.contains("单行条例")
                || text.contains("经济特区") || text.contains("浦东新区") || text.contains("自由贸易港");
    }

    private static boolean looksLikeTocLine(String text) {
        String s = text.replaceAll("\\s+", "");
        return s.length() <= 80 && s.matches(".*第[一二三四五六七八九十百千万零〇两0-9]+条.{0,30}");
    }

    private static boolean looksLikeMetadata(String text) {
        String s = text == null ? "" : text.trim();
        if (!StringUtils.hasText(s)) {
            return true;
        }
        if (s.matches("\\d{4}-\\d{2}-\\d{2}.*") || s.matches("[0-9a-fA-F-]{16,}")) {
            return true;
        }
        if (s.matches("(?i).*(?:\\.docx|\\.pdf|\\.ofd|/prod/\\d{8}/).*")) {
            return true;
        }
        return s.startsWith("http://") || s.startsWith("https://") || s.startsWith("WZWS");
    }

    /** 将「第 一 条」规范为「第一条」，便于检索与卡片展示一致 */
    private static String normalizeArticleNoToken(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("\\s+", "").trim();
    }

    private String inferCategory(String lawName, String content, String level) {
        String text = safe(lawName) + safe(content) + safe(level);
        if (text.contains("妇女") || text.contains("女职工") || text.contains("母婴") || text.contains("家庭暴力")) {
            return "社会法";
        }
        if (text.contains("劳动") || text.contains("工伤") || text.contains("社会保险") || text.contains("工资")) {
            return "劳动法";
        }
        if (text.contains("民法") || text.contains("合同") || text.contains("借款") || text.contains("婚姻")
                || text.contains("继承") || text.contains("物权") || text.contains("侵权")) {
            return "民法";
        }
        if (text.contains("行政")) return "行政法";
        if (text.contains("刑法") || text.contains("犯罪")) return "刑法";
        if (text.contains("诉讼") || text.contains("程序")) return "诉讼与非诉程序法";
        if (StringUtils.hasText(level)) return level;
        return "综合";
    }

    private static String mapStatus(Integer sxx) {
        if (sxx == null) return "现行有效";
        return switch (sxx) {
            case 3 -> "现行有效";
            case 4 -> "尚未生效";
            case 2 -> "已修改";
            case 1 -> "已废止";
            default -> "状态码" + sxx;
        };
    }

    private static LocalDate parseDate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String s = raw.trim();
        try {
            return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private ArrayNode intArray(List<Integer> values) {
        ArrayNode arr = objectMapper.createArrayNode();
        if (values != null) {
            for (Integer value : values) {
                if (value != null) {
                    arr.add(value);
                }
            }
        }
        return arr;
    }

    private static int normalizeSearchRange(LawSyncRequest req) {
        Integer range = req.getNpcSearchRange();
        if (range == null) {
            return StringUtils.hasText(req.getNpcSearchContent()) ? 2 : 1;
        }
        return range == 1 || range == 2 ? range : 2;
    }

    private static int normalizeSearchType(LawSyncRequest req) {
        Integer type = req.getNpcSearchType();
        return type != null && (type == 1 || type == 2) ? type : 2;
    }

    private static String cleanLawName(String title) {
        String t = htmlText(title);
        t = t.replaceFirst("^《", "").replaceFirst("》$", "");
        t = t.replaceAll("\\s*-\\s*国家法律法规数据库.*$", "");
        return StringUtils.hasText(t) ? t.trim() : "未知法规";
    }

    private static String normalizePlainText(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String s = raw
                .replace('\uFEFF', ' ')
                .replace('\u00A0', ' ')
                .replace('\u3000', ' ')
                .replace('\u2003', ' ')
                .replace('\u2002', ' ')
                .replace('\u2009', ' ')
                .replace('\u202F', ' ')
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "");
        return s.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n\\s*\\n+", "\n")
                .trim();
    }

    private static String htmlText(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        return Jsoup.parse(raw).text().trim();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean isRetriable(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof ResourceAccessException
                    || c instanceof java.net.SocketTimeoutException
                    || c instanceof java.net.ConnectException
                    || c instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    private void sleepBetweenDetails(List<String> failed) {
        int jitter = detailDelayJitterMs > 0 ? ThreadLocalRandom.current().nextInt(detailDelayJitterMs + 1) : 0;
        int total = detailDelayMs + jitter;
        if (total <= 0) {
            return;
        }
        try {
            Thread.sleep(total);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed.add("同步被中断");
        }
    }

    private static void ensureCookieManager() {
        if (CookieHandler.getDefault() == null) {
            CookieManager manager = new CookieManager();
            manager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
            CookieHandler.setDefault(manager);
        }
    }

    private static String summary(Throwable e) {
        if (e == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Throwable c = e; c != null && sb.length() < 500; c = c.getCause()) {
            if (!sb.isEmpty()) {
                sb.append(" | ");
            }
            sb.append(c.getClass().getSimpleName()).append(": ");
            sb.append(StringUtils.hasText(c.getMessage()) ? c.getMessage() : "(无消息)");
        }
        return sb.toString();
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws Exception;
    }

    private enum LocalRegulationsPolicy {
        SKIP, ALLOW;

        static LocalRegulationsPolicy parse(String raw) {
            if (!StringUtils.hasText(raw)) {
                return SKIP;
            }
            String s = raw.trim().toLowerCase();
            return switch (s) {
                case "allow", "all", "包含" -> ALLOW;
                default -> SKIP;
            };
        }
    }

    private record NpcRow(String bbbs,
                          String title,
                          String level,
                          String publishDate,
                          String effectiveDate,
                          Integer statusCode,
                          Integer flfgCodeId) {
    }

    private record NpcDetail(String title, String detailUrl, JsonNode data, String plainText) {
    }
}
