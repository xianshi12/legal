package com.fatongai.legalassistant.law.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fatongai.legalassistant.law.LawRegionCatalog;
import com.fatongai.legalassistant.law.dto.LawMetaResponse;
import com.fatongai.legalassistant.law.dto.LawSearchResponse;
import com.fatongai.legalassistant.law.dto.LawSearchResult;
import com.fatongai.legalassistant.law.dto.LawTagView;
import com.fatongai.legalassistant.law.dto.LawVersionView;
import com.fatongai.legalassistant.law.entity.LawArticle;
import com.fatongai.legalassistant.law.entity.LawArticleTag;
import com.fatongai.legalassistant.law.entity.LawArticleText;
import com.fatongai.legalassistant.law.entity.LawTag;
import com.fatongai.legalassistant.law.mapper.LawArticleMapper;
import com.fatongai.legalassistant.law.mapper.LawArticleTagMapper;
import com.fatongai.legalassistant.law.mapper.LawArticleTextMapper;
import com.fatongai.legalassistant.law.mapper.LawTagMapper;
import com.fatongai.legalassistant.law.support.LawInterpretationTexts;
import com.fatongai.legalassistant.rag.service.RagKnowledgeService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.text.Collator;
import java.util.Locale;
import java.util.Set;

@Service
public class LawArticleService implements SearchProvider {
    /** 国家法律法规数据库（页面爬取链路）写入的 sourceName。 */
    public static final String SOURCE_NPC_FLK = "国家法律法规数据库";
    /** 启动种子 / 离线内置条目的来源名称。 */
    public static final String SOURCE_BUILTIN = "内置基础库";
    /** 内置条目来源 URL 前缀（与前端「无外链」判断一致）。 */
    public static final String SOURCE_URL_SEED_PREFIX = "seed://";

    private final LawArticleMapper mapper;
    private final LawArticleTextMapper textMapper;
    private final LawTagMapper tagMapper;
    private final LawArticleTagMapper articleTagMapper;
    private final QwenEmbeddingService embeddingService;
    private final LawColloquialInterpretationService colloquialInterpretationService;
    private final RagKnowledgeService ragKnowledgeService;
    private final LawArticleExternalBodyResolver bodyResolver;
    private final LawArticleCacheService lawArticleCacheService;
    private final boolean seedDataOnEmpty;
    private volatile LocalDateTime lastSyncAt;

    public LawArticleService(LawArticleMapper mapper,
                             LawArticleTextMapper textMapper,
                             LawTagMapper tagMapper,
                             LawArticleTagMapper articleTagMapper,
                             QwenEmbeddingService embeddingService,
                             LawColloquialInterpretationService colloquialInterpretationService,
                             RagKnowledgeService ragKnowledgeService,
                             @Lazy LawArticleExternalBodyResolver bodyResolver,
                             LawArticleCacheService lawArticleCacheService,
                             @Value("${app.law.seed-data-on-empty:false}") boolean seedDataOnEmpty) {
        this.mapper = mapper;
        this.textMapper = textMapper;
        this.tagMapper = tagMapper;
        this.articleTagMapper = articleTagMapper;
        this.embeddingService = embeddingService;
        this.colloquialInterpretationService = colloquialInterpretationService;
        this.ragKnowledgeService = ragKnowledgeService;
        this.bodyResolver = bodyResolver;
        this.lawArticleCacheService = lawArticleCacheService;
        this.seedDataOnEmpty = seedDataOnEmpty;
    }

    @PostConstruct
    @Transactional
    public void ensureSeedData() {
        Long count = mapper.selectCount(null);
        if (count != null && count > 0) {
            refreshLastSyncAt();
            return;
        }
        if (!seedDataOnEmpty) {
            refreshLastSyncAt();
            return;
        }
        for (LawArticle law : seedArticles()) {
            fillCreate(law);
            mapper.insert(law);
        }
        updateMissingEmbeddings(20);
        refreshLastSyncAt();
        lawArticleCacheService.evictAllAfterCommit();
    }

    /**
     * 删除内置 / 离线示例法条：{@code source_url} 以 {@value #SOURCE_URL_SEED_PREFIX} 开头，或 {@code source_name} 为 {@value #SOURCE_BUILTIN}。
     *
     * @return 删除行数
     */
    @Transactional
    public int deleteSeedAndBuiltinArticles() {
        LambdaQueryWrapper<LawArticle> w = new LambdaQueryWrapper<>();
        w.and(q -> q.likeRight(LawArticle::getSourceUrl, SOURCE_URL_SEED_PREFIX)
                .or()
                .eq(LawArticle::getSourceName, SOURCE_BUILTIN));
        int removed = mapper.delete(w);
        refreshLastSyncAt();
        lawArticleCacheService.evictAllAfterCommit();
        return removed;
    }

    /**
     * 兼容旧调用（如 LegalBasisMatcher）：单状态参数转为列表筛选。
     */
    public LawSearchResponse search(String query,
                                    String searchType,
                                    List<String> categories,
                                    String status,
                                    String region,
                                    String sort,
                                    Integer page,
                                    Integer size) {
        List<String> st = null;
        if (StringUtils.hasText(status) && !"全部".equals(status.trim())) {
            st = List.of(status.trim());
        }
        return search(query, searchType, categories, st, null, region, sort,
                "fuzzy", null, null, "any", null,
                null, null, null, null,
                false, page, size);
    }

    public LawSearchResponse search(String query,
                                    String searchType,
                                    List<String> categories,
                                    List<String> statuses,
                                    List<String> issuingBodies,
                                    String region,
                                    String sort,
                                    String matchMode,
                                    String level,
                                    String lawName,
                                    String keywordMode,
                                    String advLawTitle,
                                    LocalDate publishDateFrom,
                                    LocalDate publishDateTo,
                                    LocalDate effectiveDateFrom,
                                    LocalDate effectiveDateTo,
                                    Boolean includeHistory,
                                    Integer page,
                                    Integer size) {
        String cacheKey = lawArticleCacheService.key("search", LawArticleCacheService.orderedMap(
                "query", query,
                "searchType", searchType,
                "categories", categories,
                "statuses", statuses,
                "issuingBodies", issuingBodies,
                "region", region,
                "sort", sort,
                "matchMode", matchMode,
                "level", level,
                "lawName", lawName,
                "keywordMode", keywordMode,
                "advLawTitle", advLawTitle,
                "publishDateFrom", publishDateFrom,
                "publishDateTo", publishDateTo,
                "effectiveDateFrom", effectiveDateFrom,
                "effectiveDateTo", effectiveDateTo,
                "includeHistory", includeHistory,
                "page", page,
                "size", size
        ));
        LawSearchResponse cached = lawArticleCacheService.get(cacheKey, LawSearchResponse.class);
        if (cached != null) {
            return cached;
        }
        LawSearchResponse response = doSearch(query, searchType, categories, statuses, issuingBodies, region, sort,
                matchMode, level, lawName, keywordMode, advLawTitle,
                publishDateFrom, publishDateTo, effectiveDateFrom, effectiveDateTo,
                includeHistory, page, size);
        lawArticleCacheService.put(cacheKey, response);
        return response;
    }

    private LawSearchResponse doSearch(String query,
                                       String searchType,
                                       List<String> categories,
                                       List<String> statuses,
                                       List<String> issuingBodies,
                                       String region,
                                       String sort,
                                       String matchMode,
                                       String level,
                                       String lawName,
                                       String keywordMode,
                                       String advLawTitle,
                                       LocalDate publishDateFrom,
                                       LocalDate publishDateTo,
                                       LocalDate effectiveDateFrom,
                                       LocalDate effectiveDateTo,
                                       Boolean includeHistory,
                                       Integer page,
                                       Integer size) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 10 : Math.min(size, 10);
        boolean fuzzy = !"exact".equalsIgnoreCase(matchMode);
        boolean allKeywords = "all".equalsIgnoreCase(keywordMode);
        boolean history = Boolean.TRUE.equals(includeHistory);
        String q = query == null ? "" : query.trim();
        LambdaQueryWrapper<LawArticle> wrapper = new LambdaQueryWrapper<>();
        selectLightColumns(wrapper);
        applyEffectiveFilter(wrapper, statuses, history);
        applySqlFacetFilters(wrapper, categories, statuses, issuingBodies, region, level, lawName, advLawTitle,
                publishDateFrom, publishDateTo, effectiveDateFrom, effectiveDateTo);
        applyKeywordRecall(wrapper, q, searchType);
        List<LawArticle> laws = mapper.selectList(wrapper);

        List<ScoredLaw> scored = laws.stream()
                .map(law -> new ScoredLaw(law, score(law, q, searchType, List.of(), fuzzy)))
                .filter(item -> isNumberedArticleResult(item.law()))
                .filter(item -> !StringUtils.hasText(q) || item.score() > 0.02)
                .filter(item -> !allKeywords || !StringUtils.hasText(q) || allTokensHit(item.law(), q, searchType))
                .toList();

        Comparator<ScoredLaw> comparator = buildSortComparator(sort);
        List<ScoredLaw> ordered = scored.stream().sorted(comparator).toList();
        int totalCount = ordered.size();
        int totalPages = totalCount == 0 ? 1 : (totalCount + s - 1) / s;
        if (p > totalPages) {
            p = totalPages;
        }
        int from = Math.min((p - 1) * s, totalCount);
        int to = Math.min(from + s, totalCount);
        List<LawSearchResult> items = ordered.subList(from, to).stream()
                .map(this::toListResult)
                .toList();
        return new LawSearchResponse(
                q,
                StringUtils.hasText(searchType) ? searchType : "fullText",
                p,
                s,
                totalCount,
                embeddingService.isEnabled(),
                lastSyncAt,
                items,
                ragKnowledgeService.referenceLines(q, "law", 4)
        );
    }

    private LawSearchResult toListResult(ScoredLaw item) {
        LawArticle law = item.law();
        String excerpt = cleanArticleExcerpt(law.getArticleSummary());
        String interp = law.getInterpretationSummary();
        if (isBadArticleExcerpt(excerpt) || isBadInterpretation(interp)) {
            String body = resolveArticleBodyForCard(law);
            if (!isBadArticleExcerpt(body)) {
                excerpt = cleanArticleExcerpt(body);
                interp = LawArticleDigestHelper.colloquialInterpretation(law.getLawName(), law.getArticleNo(), excerpt);
                backfillLightExcerpt(law, excerpt, interp);
            }
        }
        if (!StringUtils.hasText(excerpt)) {
            excerpt = firstNonBlank(law.getTitle(), "（暂未解析到正式条文正文，请打开详情或来源页面核对）");
        }
        if (!StringUtils.hasText(interp) || isBadInterpretation(interp)) {
            interp = LawArticleDigestHelper.colloquialInterpretation(law.getLawName(), law.getArticleNo(), excerpt);
        }
        interp = LawInterpretationTexts.extractCoreInterpretation(interp);
        return LawSearchResult.of(law, item.score())
                .withContentAndInterpretation(clamp(excerpt, 520), clamp(interp, 520), false);
    }

    private boolean isNumberedArticleResult(LawArticle law) {
        if (law == null) {
            return false;
        }
        String articleNo = safe(law.getArticleNo()).trim();
        if (!StringUtils.hasText(articleNo) || "全文".equals(articleNo)) {
            return false;
        }
        if (law.getArticleOrdinal() != null) {
            return true;
        }
        return LawArticleCitationNormalizer.parseArticleOrdinal(articleNo) != null;
    }

    private String resolveArticleBodyForCard(LawArticle law) {
        if (law == null) {
            return "";
        }
        LawArticleText text = selectTextByArticleId(law.getId());
        if (text != null && StringUtils.hasText(text.getFullContent())) {
            String slice = LawArticleBodyExtractor.extractArticleBody(text.getFullContent(), law.getArticleNo(), 1400);
            if (!isBadArticleExcerpt(slice)) {
                return slice;
            }
        }
        String resolved = bodyResolver.resolveArticleSlice(law, 1400);
        return isBadArticleExcerpt(resolved) ? "" : resolved;
    }

    private void backfillLightExcerpt(LawArticle law, String excerpt, String interpretation) {
        if (law == null || law.getId() == null || !StringUtils.hasText(excerpt)) {
            return;
        }
        try {
            LawArticle patch = new LawArticle();
            patch.setId(law.getId());
            patch.setArticleSummary(clamp(excerpt, 900));
            patch.setInterpretationSummary(clamp(interpretation, 900));
            patch.setKeywordIndex(clamp(String.join(" ",
                    safe(law.getLawName()), safe(law.getArticleNo()), safe(law.getTitle()),
                    safe(law.getScenarios()), safe(law.getCauseOfAction()), safe(law.getCategory()),
                    safe(law.getLevel()), safe(law.getIssuingBody()), excerpt), 1800));
            patch.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(patch);
            law.setArticleSummary(patch.getArticleSummary());
            law.setInterpretationSummary(patch.getInterpretationSummary());
            law.setKeywordIndex(patch.getKeywordIndex());
        } catch (Exception ignored) {
            // 搜索卡片的即时修复不能影响主查询。
        }
    }

    private String cleanArticleExcerpt(String text) {
        String s = LawArticleDigestHelper.stripTechnicalNoise(text);
        if (!StringUtils.hasText(s)) {
            return "";
        }
        s = s.replaceAll("\\s*([。；;])\\s*", "$1")
                .replaceAll("\\s+", " ")
                .trim();
        return LawInterpretationTexts.normalizeCjkInline(s);
    }

    private boolean isBadArticleExcerpt(String text) {
        if (!StringUtils.hasText(text)) {
            return true;
        }
        String s = LawArticleDigestHelper.stripTechnicalNoise(text);
        if (!StringUtils.hasText(s) || s.length() < 18) {
            return true;
        }
        if (LawArticleDigestHelper.looksLikeSparseArticleHeadingList(s)) {
            return true;
        }
        String compact = s.replaceAll("\\s+", "");
        int headingCount = 0;
        java.util.regex.Matcher m = LawArticleBodyExtractor.ARTICLE_HEADING.matcher(compact);
        while (m.find()) {
            headingCount++;
        }
        String withoutHeadings = LawArticleBodyExtractor.ARTICLE_HEADING.matcher(compact).replaceAll("");
        String substantive = withoutHeadings.replaceAll("[、，,。；;：:（）()《》0-9一二三四五六七八九十百千万零〇两章节编条款项目的附则]+", "");
        return headingCount >= 2 && substantive.length() < 16;
    }

    private boolean isBadInterpretation(String text) {
        if (!StringUtils.hasText(text)) {
            return true;
        }
        String s = text.trim();
        return s.contains("疑似仅为条号")
                || s.contains("条号目录")
                || s.contains("缺少可读的条文正文")
                || s.contains("暂时没有识别到清晰的核心法律内容");
    }

    public LawSearchResult detail(Long id) {
        return detail(id, "auto");
    }

    /**
     * @param interpretationSource {@code auto}：按来源 URL（若可访问）截取条文正文后生成通俗解读（可调用大模型）；{@code stored}：仅返回库中已存解读
     */
    public LawSearchResult detail(Long id, String interpretationSource) {
        String cacheKey = lawArticleCacheService.detailKey(id, interpretationSource);
        LawSearchResult cached = lawArticleCacheService.get(cacheKey, LawSearchResult.class);
        if (cached != null) {
            return cached;
        }
        LawSearchResult result = doDetail(id, interpretationSource);
        lawArticleCacheService.put(cacheKey, result);
        return result;
    }

    private LawSearchResult doDetail(Long id, String interpretationSource) {
        LawArticle law = mapper.selectById(id);
        if (law == null) {
            throw new IllegalArgumentException("未找到该法条");
        }
        LawArticleText text = selectTextByArticleId(id);
        String body = "";
        if (text != null && StringUtils.hasText(text.getFullContent())) {
            body = text.getFullContent();
        }
        if (!StringUtils.hasText(body)) {
            body = bodyResolver.resolveArticleSlice(law, 200_000);
        }
        if (!StringUtils.hasText(body) && StringUtils.hasText(law.getContent())) {
            body = law.getContent();
        }
        if (text != null) {
            law.setContentRef(firstNonBlank(law.getContentRef(), text.getContentRef()));
            law.setContentHash(firstNonBlank(law.getContentHash(), text.getContentHash()));
        }
        String storedInterp = firstNonBlank(text == null ? "" : text.getFullInterpretation(), law.getInterpretation());
        LawSearchResult base = LawSearchResult.of(law, 1.0).withContentAndInterpretation(
                StringUtils.hasText(body) ? body : "（正文需联网按映射解析）",
                normalizeDetailInterpretationBody(storedInterp),
                true);
        String validityNotice = buildValidityNotice(law);
        LawSearchResult withNotice = StringUtils.hasText(validityNotice)
                ? base.withValidityNotice(validityNotice)
                : base;
        if (StringUtils.hasText(interpretationSource) && "stored".equalsIgnoreCase(interpretationSource.trim())) {
            return withNotice;
        }
        String interp = StringUtils.hasText(body)
                ? colloquialInterpretationService.buildInterpretationFromBody(law, body, true)
                : colloquialInterpretationService.buildInterpretation(law, true, null, true);
        return withNotice.withContentAndInterpretation(withNotice.content(), normalizeDetailInterpretationBody(interp), true);
    }

    /**
     * 是否已有任意法条以该 URL 为来源（用于 URL 同步去重：同一详情页多条目共享 sourceUrl）。
     */
    public boolean hasArticlesForSourceUrl(String sourceUrl) {
        if (!StringUtils.hasText(sourceUrl)) {
            return false;
        }
        Long c = mapper.selectCount(new LambdaQueryWrapper<LawArticle>()
                .eq(LawArticle::getSourceUrl, sourceUrl.trim()));
        return c != null && c > 0;
    }

    public boolean hasSplitArticlesForSourceUrl(String sourceUrl) {
        if (!StringUtils.hasText(sourceUrl)) {
            return false;
        }
        List<LawArticle> rows = mapper.selectList(new LambdaQueryWrapper<LawArticle>()
                .eq(LawArticle::getSourceUrl, sourceUrl.trim())
                .select(LawArticle::getArticleNo, LawArticle::getArticleOrdinal));
        Set<String> articleNos = new HashSet<>();
        for (LawArticle row : rows) {
            String no = safe(row.getArticleNo()).trim();
            if (!"全文".equals(no) && row.getArticleOrdinal() != null) {
                articleNos.add(no);
            }
        }
        return articleNos.size() >= 2;
    }

    /**
     * 在已入库的 NPC 页面数据中，哪些 {@code bbbs} 已出现（通过 sourceUrl 包含该 id 判断）。
     */
    public Set<String> findNpcBbbsAlreadyPresent(Collection<String> bbbsCandidates) {
        Set<String> wanted = new HashSet<>();
        if (bbbsCandidates != null) {
            for (String b : bbbsCandidates) {
                if (StringUtils.hasText(b)) {
                    wanted.add(b.trim());
                }
            }
        }
        if (wanted.isEmpty()) {
            return Set.of();
        }
        LambdaQueryWrapper<LawArticle> w = new LambdaQueryWrapper<LawArticle>()
                .eq(LawArticle::getSourceName, SOURCE_NPC_FLK)
                .select(LawArticle::getSourceUrl, LawArticle::getExternalRef);
        w.and(outer -> {
            boolean first = true;
            for (String bbbs : wanted) {
                String b = bbbs.trim();
                if (!StringUtils.hasText(b)) {
                    continue;
                }
                if (first) {
                    outer.and(inner -> inner.like(LawArticle::getSourceUrl, b)
                            .or().eq(LawArticle::getExternalRef, "npc:" + b));
                    first = false;
                } else {
                    outer.or(inner -> inner.like(LawArticle::getSourceUrl, b)
                            .or().eq(LawArticle::getExternalRef, "npc:" + b));
                }
            }
        });
        List<LawArticle> hits = mapper.selectList(w);
        Set<String> present = new HashSet<>();
        for (LawArticle row : hits) {
            String url = row.getSourceUrl();
            String ref = row.getExternalRef();
            for (String bbbs : wanted) {
                String b = bbbs.trim();
                if (!StringUtils.hasText(b)) {
                    continue;
                }
                if ((StringUtils.hasText(url) && url.contains(b)) || ("npc:" + b).equals(ref)) {
                    present.add(b);
                }
            }
        }
        return present;
    }

    /**
     * NPC 同步跳过判断使用：只有同一法规已经拆成「第...条」级别的多条记录时才算完成。
     * 旧数据里可能只有一条「全文」占位，必须允许重新抓取并拆分，否则前端会继续显示一张大卡。
     */
    public Set<String> findNpcBbbsWithSplitArticlesPresent(Collection<String> bbbsCandidates) {
        Set<String> wanted = normalizeBbbsSet(bbbsCandidates);
        if (wanted.isEmpty()) {
            return Set.of();
        }
        List<LawArticle> hits = selectNpcRowsForBbbs(wanted);
        Map<String, Set<String>> splitArticleNos = new HashMap<>();
        for (LawArticle row : hits) {
            String matched = matchedBbbs(row, wanted);
            if (!StringUtils.hasText(matched)) {
                continue;
            }
            String articleNo = safe(row.getArticleNo()).trim();
            if (!"全文".equals(articleNo) && row.getArticleOrdinal() != null) {
                splitArticleNos.computeIfAbsent(matched, k -> new HashSet<>()).add(articleNo);
            }
        }
        Set<String> present = new HashSet<>();
        splitArticleNos.forEach((bbbs, articleNos) -> {
            if (articleNos.size() >= 2) {
                present.add(bbbs);
            }
        });
        return present;
    }

    @Transactional
    public int deleteNpcFullTextPlaceholders(String bbbs, String sourceUrl) {
        Set<String> wanted = normalizeBbbsSet(StringUtils.hasText(bbbs) ? List.of(bbbs) : List.of());
        if (wanted.isEmpty() && !StringUtils.hasText(sourceUrl)) {
            return 0;
        }
        List<LawArticle> candidates = wanted.isEmpty()
                ? mapper.selectList(new LambdaQueryWrapper<LawArticle>()
                .eq(StringUtils.hasText(sourceUrl), LawArticle::getSourceUrl, sourceUrl)
                .select(LawArticle::getId, LawArticle::getArticleNo, LawArticle::getArticleOrdinal))
                : selectNpcRowsForBbbs(wanted);
        List<Long> ids = candidates.stream()
                .filter(row -> row.getId() != null)
                .filter(row -> "全文".equals(safe(row.getArticleNo()).trim()) || row.getArticleOrdinal() == null)
                .map(LawArticle::getId)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return 0;
        }
        textMapper.delete(new LambdaQueryWrapper<LawArticleText>().in(LawArticleText::getArticleId, ids));
        articleTagMapper.delete(new LambdaQueryWrapper<LawArticleTag>().in(LawArticleTag::getArticleId, ids));
        int removed = mapper.deleteBatchIds(ids);
        refreshLastSyncAt();
        lawArticleCacheService.evictAllAfterCommit();
        return removed;
    }

    private Set<String> normalizeBbbsSet(Collection<String> bbbsCandidates) {
        Set<String> wanted = new HashSet<>();
        if (bbbsCandidates != null) {
            for (String b : bbbsCandidates) {
                if (StringUtils.hasText(b)) {
                    wanted.add(b.trim());
                }
            }
        }
        return wanted;
    }

    private List<LawArticle> selectNpcRowsForBbbs(Set<String> wanted) {
        if (wanted == null || wanted.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<LawArticle> w = new LambdaQueryWrapper<LawArticle>()
                .eq(LawArticle::getSourceName, SOURCE_NPC_FLK)
                .select(
                        LawArticle::getId,
                        LawArticle::getArticleNo,
                        LawArticle::getArticleOrdinal,
                        LawArticle::getSourceUrl,
                        LawArticle::getExternalRef
                );
        w.and(outer -> {
            boolean first = true;
            for (String bbbs : wanted) {
                String b = bbbs.trim();
                if (!StringUtils.hasText(b)) {
                    continue;
                }
                if (first) {
                    outer.and(inner -> inner.like(LawArticle::getSourceUrl, b)
                            .or().eq(LawArticle::getExternalRef, "npc:" + b));
                    first = false;
                } else {
                    outer.or(inner -> inner.like(LawArticle::getSourceUrl, b)
                            .or().eq(LawArticle::getExternalRef, "npc:" + b));
                }
            }
        });
        return mapper.selectList(w);
    }

    private String matchedBbbs(LawArticle row, Set<String> wanted) {
        if (row == null || wanted == null || wanted.isEmpty()) {
            return "";
        }
        String url = safe(row.getSourceUrl());
        String ref = safe(row.getExternalRef());
        for (String bbbs : wanted) {
            String b = bbbs.trim();
            if ((StringUtils.hasText(url) && url.contains(b)) || ("npc:" + b).equals(ref)) {
                return b;
            }
        }
        return "";
    }

    public LawMetaResponse meta() {
        String cacheKey = lawArticleCacheService.metaKey();
        LawMetaResponse cached = lawArticleCacheService.get(cacheKey, LawMetaResponse.class);
        if (cached != null) {
            return cached;
        }
        LawMetaResponse result = doMeta();
        lawArticleCacheService.put(cacheKey, result);
        return result;
    }

    private LawMetaResponse doMeta() {
        List<LawArticle> laws = mapper.selectList(new QueryWrapper<LawArticle>().select(
                "category", "level", "issuing_body", "region", "status", "source_updated_at"
        ));
        return new LawMetaResponse(
                distinct(laws.stream().map(LawArticle::getCategory).toList()),
                distinct(laws.stream().map(LawArticle::getLevel).toList()),
                LawRegionCatalog.mergeWithDb(distinct(laws.stream().map(LawArticle::getRegion).toList())),
                distinct(laws.stream().map(LawArticle::getStatus).toList()),
                distinct(laws.stream().map(LawArticle::getIssuingBody).toList()),
                mapper.selectCount(null),
                embeddingService.isEnabled(),
                embeddingService.model(),
                lastSyncAt,
                NpcFlkLawImporter.NPC_PUBLIC_SEARCH_URL
        );
    }

    public List<LawTagView> tagTree(String tagType) {
        String cacheKey = lawArticleCacheService.tagsKey(tagType);
        List<LawTagView> cached = lawArticleCacheService.get(cacheKey, lawArticleCacheService.listType(LawTagView.class));
        if (cached != null) {
            return cached;
        }
        List<LawTagView> result = doTagTree(tagType);
        lawArticleCacheService.put(cacheKey, result);
        return result;
    }

    private List<LawTagView> doTagTree(String tagType) {
        LambdaQueryWrapper<LawTag> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(tagType)) {
            w.eq(LawTag::getTagType, tagType.trim());
        }
        w.orderByAsc(LawTag::getTagType, LawTag::getParentId, LawTag::getSortOrder, LawTag::getName);
        List<LawTag> tags = tagMapper.selectList(w);
        Map<Long, List<LawTag>> byParent = new HashMap<>();
        for (LawTag tag : tags) {
            byParent.computeIfAbsent(tag.getParentId(), k -> new ArrayList<>()).add(tag);
        }
        return buildTagViews(byParent, null);
    }

    public List<LawVersionView> versionHistory(Long articleId) {
        String cacheKey = lawArticleCacheService.versionsKey(articleId);
        List<LawVersionView> cached = lawArticleCacheService.get(cacheKey, lawArticleCacheService.listType(LawVersionView.class));
        if (cached != null) {
            return cached;
        }
        List<LawVersionView> result = doVersionHistory(articleId);
        lawArticleCacheService.put(cacheKey, result);
        return result;
    }

    private List<LawVersionView> doVersionHistory(Long articleId) {
        LawArticle current = mapper.selectById(articleId);
        if (current == null) {
            throw new IllegalArgumentException("未找到该法条");
        }
        LambdaQueryWrapper<LawArticle> w = new LambdaQueryWrapper<>();
        selectLightColumns(w);
        if (StringUtils.hasText(current.getStableArticleKey())) {
            w.eq(LawArticle::getStableArticleKey, current.getStableArticleKey());
        } else {
            w.eq(LawArticle::getLawName, current.getLawName())
                    .eq(LawArticle::getNormalizedArticleNo, current.getNormalizedArticleNo());
        }
        w.orderByDesc(LawArticle::getEffectiveDate).orderByDesc(LawArticle::getId);
        return mapper.selectList(w).stream()
                .map(law -> new LawVersionView(
                        law.getId(),
                        law.getLawName(),
                        law.getArticleNo(),
                        law.getStatus(),
                        law.getVersionNo(),
                        law.getEffectiveDate(),
                        law.getEffectiveTo(),
                        Boolean.TRUE.equals(law.getCurrentEffective()),
                        law.getSourceName(),
                        law.getSourceUrl()
                ))
                .toList();
    }

    @Transactional
    public int rebuildLightIndex(int limit) {
        List<LawArticle> laws = mapper.selectList(new LambdaQueryWrapper<LawArticle>()
                .last("limit " + Math.max(1, Math.min(limit, 500))));
        int changed = 0;
        for (LawArticle law : laws) {
            String fullContent = law.getContent();
            if (!StringUtils.hasText(fullContent) || isBadArticleExcerpt(fullContent)) {
                fullContent = resolveArticleBodyForCard(law);
            }
            enrichLightFields(law, fullContent);
            law.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(law);
            upsertText(law, fullContent, null);
            changed++;
        }
        if (changed > 0) {
            lawArticleCacheService.evictAllAfterCommit();
        }
        return changed;
    }

    @Transactional
    public int upsertAll(List<LawArticle> articles) {
        int changed = 0;
        for (LawArticle article : articles) {
            upsertSingleArticle(article);
            changed++;
        }
        refreshLastSyncAt();
        if (changed > 0) {
            lawArticleCacheService.evictAllAfterCommit();
        }
        return changed;
    }

    private LawArticle findExistingForUpsert(LawArticle article) {
        return mapper.selectOne(new LambdaQueryWrapper<LawArticle>()
                .eq(LawArticle::getLawName, article.getLawName())
                .eq(LawArticle::getArticleNo, article.getArticleNo())
                .eq(StringUtils.hasText(article.getSourceUrl()), LawArticle::getSourceUrl, article.getSourceUrl())
                .last("limit 1"));
    }

    private void mergeAndUpdateArticle(LawArticle article, String fullContent, LawArticle old) {
        article.setId(old.getId());
        article.setCreatedAt(old.getCreatedAt());
        article.setUpdatedAt(LocalDateTime.now());
        article.setVersionNo(firstNonBlank(article.getVersionNo(), old.getVersionNo(), "current"));
        article.setStableArticleKey(firstNonBlank(article.getStableArticleKey(), old.getStableArticleKey()));
        article.setNormalizedArticleNo(firstNonBlank(article.getNormalizedArticleNo(), old.getNormalizedArticleNo()));
        article.setArticleOrdinal(article.getArticleOrdinal() == null ? old.getArticleOrdinal() : article.getArticleOrdinal());
        article.setCurrentEffective(article.getCurrentEffective() == null ? old.getCurrentEffective() : article.getCurrentEffective());
        if (!sameText(old, article)) {
            embedArticle(article);
        } else {
            article.setVectorJson(old.getVectorJson());
            article.setVectorModel(old.getVectorModel());
        }
        mapper.updateById(article);
        upsertText(article, fullContent, old);
    }

    /**
     * 单条写入：先查再插，与其它事务并发时可能同时读到空行；插入遇唯一键冲突则改为更新。
     */
    private void upsertSingleArticle(LawArticle article) {
        String fullContent = article.getContent();
        enrichLightFields(article, fullContent);
        LawArticle old = findExistingForUpsert(article);
        if (old != null) {
            mergeAndUpdateArticle(article, fullContent, old);
            return;
        }
        fillCreate(article);
        embedArticle(article);
        try {
            mapper.insert(article);
        } catch (DuplicateKeyException e) {
            old = findExistingForUpsert(article);
            if (old == null) {
                throw e;
            }
            mergeAndUpdateArticle(article, fullContent, old);
            return;
        }
        upsertText(article, fullContent, null);
    }

    @Transactional
    public int updateMissingEmbeddings(int limit) {
        if (!embeddingService.isEnabled()) return 0;
        List<LawArticle> laws = mapper.selectList(new LambdaQueryWrapper<LawArticle>()
                .and(w -> w.isNull(LawArticle::getVectorJson).or().eq(LawArticle::getVectorJson, ""))
                .last("limit " + Math.max(1, Math.min(limit, 100))));
        int count = 0;
        for (LawArticle law : laws) {
            embedArticle(law);
            law.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(law);
            count++;
        }
        if (count > 0) {
            lawArticleCacheService.evictAllAfterCommit();
        }
        return count;
    }

    private void selectLightColumns(LambdaQueryWrapper<LawArticle> wrapper) {
        wrapper.select(
                LawArticle::getId,
                LawArticle::getLawName,
                LawArticle::getArticleNo,
                LawArticle::getTitle,
                LawArticle::getScenarios,
                LawArticle::getExceptionsText,
                LawArticle::getCauseOfAction,
                LawArticle::getCategory,
                LawArticle::getLevel,
                LawArticle::getIssuingBody,
                LawArticle::getRegion,
                LawArticle::getStatus,
                LawArticle::getSourceName,
                LawArticle::getSourceUrl,
                LawArticle::getExternalRef,
                LawArticle::getStableArticleKey,
                LawArticle::getNormalizedArticleNo,
                LawArticle::getArticleOrdinal,
                LawArticle::getArticleSummary,
                LawArticle::getInterpretationSummary,
                LawArticle::getKeywordIndex,
                LawArticle::getSortWeight,
                LawArticle::getVersionNo,
                LawArticle::getEffectiveTo,
                LawArticle::getReplacedByArticleId,
                LawArticle::getCurrentEffective,
                LawArticle::getContentHash,
                LawArticle::getContentRef,
                LawArticle::getPublishDate,
                LawArticle::getEffectiveDate,
                LawArticle::getSourceUpdatedAt,
                LawArticle::getCreatedAt,
                LawArticle::getUpdatedAt
        );
    }

    private void applyEffectiveFilter(LambdaQueryWrapper<LawArticle> wrapper, List<String> statuses, boolean includeHistory) {
        if (includeHistory) {
            return;
        }
        boolean explicitAll = statuses != null && statuses.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .anyMatch("全部"::equals);
        boolean explicitHistoricalStatus = statuses != null && statuses.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .anyMatch(s -> !"全部".equals(s) && !"现行有效".equals(s) && !"有效".equals(s));
        if (explicitAll || explicitHistoricalStatus) {
            return;
        }
        wrapper.and(w -> w.eq(LawArticle::getCurrentEffective, true)
                .or().isNull(LawArticle::getCurrentEffective));
        wrapper.and(w -> w.isNull(LawArticle::getStatus)
                .or().notIn(LawArticle::getStatus, List.of("已废止", "失效", "已失效", "废止")));
    }

    @Override
    public void applyKeywordRecall(LambdaQueryWrapper<LawArticle> wrapper, String query, String searchType) {
        if (!StringUtils.hasText(query)) {
            return;
        }
        String q = query.trim();
        LawArticleCitationNormalizer.NormalizedCitation citation = LawArticleCitationNormalizer.parse(q);
        if ("articleNo".equalsIgnoreCase(searchType) || citation.hasArticleNo()) {
            if (StringUtils.hasText(citation.lawNameHint())) {
                String hint = citation.lawNameHint();
                String shortHint = hint.replace("中华人民共和国", "");
                wrapper.and(w -> {
                    w.like(LawArticle::getLawName, hint).or().like(LawArticle::getTitle, hint);
                    if (StringUtils.hasText(shortHint)) {
                        w.or().like(LawArticle::getLawName, shortHint).or().like(LawArticle::getTitle, shortHint);
                    }
                });
            }
            if (citation.articleOrdinal() != null || StringUtils.hasText(citation.normalizedArticleNo())) {
                wrapper.and(w -> {
                    boolean used = false;
                    if (citation.articleOrdinal() != null) {
                        w.eq(LawArticle::getArticleOrdinal, citation.articleOrdinal());
                        used = true;
                    }
                    if (StringUtils.hasText(citation.normalizedArticleNo())) {
                        if (used) {
                            w.or();
                        }
                        w.eq(LawArticle::getNormalizedArticleNo, citation.normalizedArticleNo())
                                .or().like(LawArticle::getArticleNo, citation.normalizedArticleNo().replace("第", "").replace("条", ""));
                    }
                });
            }
            return;
        }
        List<Long> taggedArticleIds = "cause".equalsIgnoreCase(searchType)
                ? articleTagMapper.findArticleIdsByTag(q, 500)
                : List.of();
        List<String> tokenList = tokens(q).stream()
                .map(String::trim)
                .filter(t -> t.length() >= 2)
                .limit(8)
                .toList();
        wrapper.and(w -> {
            w.like(LawArticle::getLawName, q)
                    .or().like(LawArticle::getArticleNo, q)
                    .or().like(LawArticle::getTitle, q)
                    .or().like(LawArticle::getArticleSummary, q)
                    .or().like(LawArticle::getInterpretationSummary, q)
                    .or().like(LawArticle::getKeywordIndex, q)
                    .or().like(LawArticle::getCauseOfAction, q)
                    .or().like(LawArticle::getScenarios, q);
            for (String token : tokenList) {
                w.or().like(LawArticle::getKeywordIndex, token)
                        .or().like(LawArticle::getArticleSummary, token)
                        .or().like(LawArticle::getInterpretationSummary, token)
                        .or().like(LawArticle::getCauseOfAction, token)
                        .or().like(LawArticle::getScenarios, token);
            }
            if (!taggedArticleIds.isEmpty()) {
                w.or().in(LawArticle::getId, taggedArticleIds);
            }
        });
    }

    private LawArticleText selectTextByArticleId(Long articleId) {
        if (articleId == null) {
            return null;
        }
        return textMapper.selectOne(new LambdaQueryWrapper<LawArticleText>()
                .eq(LawArticleText::getArticleId, articleId)
                .last("limit 1"));
    }

    private List<LawTagView> buildTagViews(Map<Long, List<LawTag>> byParent, Long parentId) {
        return byParent.getOrDefault(parentId, List.of()).stream()
                .map(tag -> new LawTagView(
                        tag.getId(),
                        tag.getCode(),
                        tag.getName(),
                        tag.getTagType(),
                        tag.getParentId(),
                        buildTagViews(byParent, tag.getId())
                ))
                .toList();
    }

    private void enrichLightFields(LawArticle article, String fullContent) {
        if (article == null) {
            return;
        }
        if (!StringUtils.hasText(article.getNormalizedArticleNo())) {
            article.setNormalizedArticleNo(LawArticleCitationNormalizer.normalizeArticleNo(article.getArticleNo()));
        }
        if (article.getArticleOrdinal() == null) {
            article.setArticleOrdinal(LawArticleCitationNormalizer.parseArticleOrdinal(article.getArticleNo()));
        }
        if (!StringUtils.hasText(article.getStableArticleKey())) {
            article.setStableArticleKey(LawArticleCitationNormalizer.stableKey(article.getLawName(), article.getArticleNo()));
        }
        if (!StringUtils.hasText(article.getVersionNo())) {
            article.setVersionNo("current");
        }
        if (article.getSortWeight() == null) {
            article.setSortWeight(0);
        }
        if (article.getCurrentEffective() == null) {
            article.setCurrentEffective(isCurrentStatus(article.getStatus()));
        }
        String clean = LawArticleDigestHelper.stripTechnicalNoise(firstNonBlank(fullContent, article.getArticleSummary(), article.getTitle()));
        if ((!StringUtils.hasText(article.getArticleSummary()) || isBadArticleExcerpt(article.getArticleSummary())) && StringUtils.hasText(clean)) {
            article.setArticleSummary(clamp(clean, 480));
        }
        if (!StringUtils.hasText(article.getInterpretationSummary()) || isBadInterpretation(article.getInterpretationSummary())) {
            article.setInterpretationSummary(clamp(LawArticleDigestHelper.colloquialInterpretation(
                    article.getLawName(), article.getArticleNo(), clean), 500));
        }
        if (!StringUtils.hasText(article.getKeywordIndex())) {
            article.setKeywordIndex(clamp(String.join(" ",
                    safe(article.getLawName()), safe(article.getArticleNo()), safe(article.getTitle()),
                    safe(article.getScenarios()), safe(article.getCauseOfAction()), safe(article.getCategory()),
                    safe(article.getLevel()), safe(article.getIssuingBody()), safe(article.getArticleSummary())), 1800));
        }
        if (StringUtils.hasText(fullContent) && !StringUtils.hasText(article.getContentHash())) {
            article.setContentHash(sha256(fullContent));
        }
        if (StringUtils.hasText(fullContent) && !StringUtils.hasText(article.getContentRef())) {
            article.setContentRef("mysql:law_article_text");
        }
        article.setContent(null);
        article.setInterpretation(null);
    }

    private void upsertText(LawArticle article, String fullContent, LawArticle old) {
        if (article == null || article.getId() == null || !StringUtils.hasText(fullContent)) {
            return;
        }
        LawArticleText row = selectTextByArticleId(article.getId());
        LocalDateTime now = LocalDateTime.now();
        if (row == null) {
            row = new LawArticleText();
            row.setArticleId(article.getId());
            row.setCreatedAt(now);
        }
        row.setContentRef(firstNonBlank(article.getContentRef(), "mysql:law_article_text"));
        row.setFullContent(fullContent);
        row.setFullInterpretation(article.getInterpretationSummary());
        row.setContentHash(article.getContentHash());
        row.setVersionNo(firstNonBlank(article.getVersionNo(), old == null ? "" : old.getVersionNo(), "current"));
        row.setUpdatedAt(now);
        if (row.getId() == null) {
            textMapper.insert(row);
        } else {
            textMapper.updateById(row);
        }
    }

    /**
     * 结构化筛选 + 高级精确标题 + 日期区间（与主检索 q 在 SQL 层 AND 组合）。
     */
    private void applySqlFacetFilters(LambdaQueryWrapper<LawArticle> wrapper,
                                      List<String> categories,
                                      List<String> statuses,
                                      List<String> issuingBodies,
                                      String region,
                                      String level,
                                      String lawNameLike,
                                      String advLawTitleExact,
                                      LocalDate publishDateFrom,
                                      LocalDate publishDateTo,
                                      LocalDate effectiveDateFrom,
                                      LocalDate effectiveDateTo) {
        if (categories != null && !categories.isEmpty()) {
            List<String> cats = categories.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .distinct()
                    .toList();
            if (!cats.isEmpty()) {
                wrapper.in(LawArticle::getCategory, cats);
            }
        }
        if (statuses != null && !statuses.isEmpty()) {
            List<String> st = statuses.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .filter(s -> !"全部".equals(s))
                    .distinct()
                    .toList();
            if (!st.isEmpty()) {
                wrapper.in(LawArticle::getStatus, st);
            }
        }
        if (issuingBodies != null && !issuingBodies.isEmpty()) {
            List<String> ib = issuingBodies.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .distinct()
                    .toList();
            if (!ib.isEmpty()) {
                wrapper.in(LawArticle::getIssuingBody, ib);
            }
        }
        applyRegionFilter(wrapper, region);
        if (StringUtils.hasText(level) && !"全部".equals(level.trim())) {
            wrapper.eq(LawArticle::getLevel, level.trim());
        }
        if (StringUtils.hasText(lawNameLike)) {
            wrapper.like(LawArticle::getLawName, lawNameLike.trim());
        }
        if (StringUtils.hasText(advLawTitleExact)) {
            wrapper.eq(LawArticle::getLawName, advLawTitleExact.trim());
        }
        if (publishDateFrom != null) {
            wrapper.ge(LawArticle::getPublishDate, publishDateFrom);
        }
        if (publishDateTo != null) {
            wrapper.le(LawArticle::getPublishDate, publishDateTo);
        }
        if (effectiveDateFrom != null) {
            wrapper.ge(LawArticle::getEffectiveDate, effectiveDateFrom);
        }
        if (effectiveDateTo != null) {
            wrapper.le(LawArticle::getEffectiveDate, effectiveDateTo);
        }
    }

    private void applyRegionFilter(LambdaQueryWrapper<LawArticle> wrapper, String region) {
        if (!StringUtils.hasText(region) || "全国".equals(region) || "全部".equals(region)) {
            return;
        }
        String r = region.trim();
        wrapper.and(w -> w.eq(LawArticle::getRegion, r)
                .or().eq(LawArticle::getRegion, "全国")
                .or().isNull(LawArticle::getRegion)
                .or().eq(LawArticle::getRegion, ""));
    }

    private Comparator<ScoredLaw> buildSortComparator(String sort) {
        Collator zh = Collator.getInstance(Locale.CHINA);
        if ("latest".equalsIgnoreCase(sort)) {
            return Comparator.comparing((ScoredLaw item) -> nullSafe(item.law().getSourceUpdatedAt())).reversed()
                    .thenComparing(ScoredLaw::score, Comparator.reverseOrder());
        }
        if ("oldest".equalsIgnoreCase(sort)) {
            return Comparator.comparing((ScoredLaw item) -> nullSafe(item.law().getSourceUpdatedAt()))
                    .thenComparing(ScoredLaw::score, Comparator.reverseOrder());
        }
        if ("lawName".equalsIgnoreCase(sort)) {
            return Comparator.comparing((ScoredLaw item) -> safe(item.law().getLawName()), zh)
                    .thenComparingInt(item -> nullSafeOrdinal(item.law()))
                    .thenComparing((ScoredLaw item) -> simplifyArticleKey(item.law().getArticleNo()), zh)
                    .thenComparing(ScoredLaw::score, Comparator.reverseOrder());
        }
        if ("articleNo".equalsIgnoreCase(sort)) {
            return Comparator.comparingInt((ScoredLaw item) -> nullSafeOrdinal(item.law()))
                    .thenComparing((ScoredLaw item) -> simplifyArticleKey(item.law().getArticleNo()), zh)
                    .thenComparing(ScoredLaw::score, Comparator.reverseOrder());
        }
        if ("publishLatest".equalsIgnoreCase(sort)) {
            return Comparator.comparing((ScoredLaw item) -> nullSafeDate(item.law().getPublishDate())).reversed()
                    .thenComparing(ScoredLaw::score, Comparator.reverseOrder());
        }
        if ("publishOldest".equalsIgnoreCase(sort)) {
            return Comparator.comparing((ScoredLaw item) -> nullSafeDate(item.law().getPublishDate()))
                    .thenComparing(ScoredLaw::score, Comparator.reverseOrder());
        }
        return Comparator.comparing(ScoredLaw::score).reversed()
                .thenComparing((ScoredLaw item) -> safe(item.law().getLawName()), zh)
                .thenComparingInt(item -> nullSafeOrdinal(item.law()))
                .thenComparing((ScoredLaw item) -> simplifyArticleKey(item.law().getArticleNo()), zh);
    }

    private LocalDate nullSafeDate(LocalDate d) {
        return d == null ? LocalDate.MIN : d;
    }

    private boolean allTokensHit(LawArticle law, String query, String searchType) {
        List<String> tokenList = tokens(query);
        boolean anyMeaningful = tokenList.stream().anyMatch(t -> normalize(t).length() >= 2);
        if (!anyMeaningful) {
            return true;
        }
        String hay = normalize(haystackForSearchType(law, searchType, true));
        for (String token : tokenList) {
            String t = normalize(token);
            if (t.length() < 2) {
                continue;
            }
            String relaxed = stripArticleNoise(hay);
            String tokenRelaxed = stripArticleNoise(t);
            if (!relaxed.contains(tokenRelaxed) && !hay.contains(t)) {
                return false;
            }
        }
        return true;
    }

    private double score(LawArticle law, String query, String searchType, List<Double> queryVector, boolean fuzzy) {
        if (!StringUtils.hasText(query)) {
            return 0.5;
        }
        String q = normalize(query);
        List<String> tokenList = tokens(query);
        String haystack = normalize(haystackForSearchType(law, searchType, true));
        String relaxedHay = stripArticleNoise(haystack);
        String relaxedQ = stripArticleNoise(q);

        if (!fuzzy) {
            boolean hit = haystack.contains(q);
            if ("articleNo".equalsIgnoreCase(searchType)) {
                String ano = normalize(safe(law.getArticleNo()));
                String anoRel = stripArticleNoise(ano);
                hit = hit || ano.contains(q) || (!relaxedQ.isEmpty() && anoRel.contains(relaxedQ));
            }
            if (!hit) {
                return 0;
            }
            double vectorScore = 0;
            if (!queryVector.isEmpty() && StringUtils.hasText(law.getVectorJson())) {
                vectorScore = embeddingService.cosine(queryVector, embeddingService.fromJson(law.getVectorJson()));
            }
            return Math.min(1.0, 0.92 + vectorScore * 0.08);
        }

        double lexical = haystack.contains(q) || (!relaxedQ.isEmpty() && relaxedHay.contains(relaxedQ)) ? 0.42 : 0;
        for (String token : tokenList) {
            String normalizedToken = normalize(token);
            if (normalizedToken.length() > 1) {
                String rt = stripArticleNoise(normalizedToken);
                if (haystack.contains(normalizedToken)
                        || (!rt.isEmpty() && relaxedHay.contains(rt))) {
                    lexical += 0.08;
                }
            }
        }
        if ("articleNo".equalsIgnoreCase(searchType)) {
            String ano = normalize(safe(law.getArticleNo()));
            String anoR = stripArticleNoise(ano);
            if (ano.contains(q) || (!relaxedQ.isEmpty() && anoR.contains(relaxedQ))) {
                lexical += 0.45;
            }
            lexical += tokenList.stream().anyMatch(token -> {
                String nt = normalize(token);
                return nt.length() > 1 && (ano.contains(nt) || stripArticleNoise(ano).contains(stripArticleNoise(nt)));
            }) ? 0.15 : 0;
        }
        if ("cause".equalsIgnoreCase(searchType)) {
            String causeHay = normalize(safe(law.getCauseOfAction()) + safe(law.getScenarios()));
            if (causeHay.contains(q)) {
                lexical += 0.35;
            }
            lexical += tokenList.stream().anyMatch(token ->
                    token.length() > 1 && causeHay.contains(normalize(token))) ? 0.18 : 0;
        }
        if (normalize(law.getLawName()).contains(q) || normalize(law.getTitle()).contains(q)) {
            lexical += 0.18;
        }
        double vectorScore = 0;
        if (!queryVector.isEmpty() && StringUtils.hasText(law.getVectorJson())) {
            vectorScore = embeddingService.cosine(queryVector, embeddingService.fromJson(law.getVectorJson()));
        }
        return Math.min(1.0, lexical + vectorScore * 0.58);
    }

    /**
     * 按检索类型拼接待匹配文本；{@code broad} 为 true 时用于模糊/词命中，覆盖主要正文字段。
     */
    private String haystackForSearchType(LawArticle law, String searchType, boolean broad) {
        if ("articleNo".equalsIgnoreCase(searchType)) {
            return String.join(" ",
                    safe(law.getLawName()), safe(law.getArticleNo()), safe(law.getTitle()));
        }
        if ("cause".equalsIgnoreCase(searchType)) {
            return String.join(" ",
                    safe(law.getCauseOfAction()), safe(law.getScenarios()),
                    safe(law.getLawName()), safe(law.getArticleNo()), safe(law.getTitle()));
        }
        if (!broad) {
            return "";
        }
        return String.join(" ",
                safe(law.getLawName()), safe(law.getArticleNo()), safe(law.getTitle()),
                safe(law.getScenarios()), safe(law.getExceptionsText()),
                safe(law.getCauseOfAction()), safe(law.getCategory()), safe(law.getLevel()),
                safe(law.getArticleSummary()), safe(law.getInterpretationSummary()), safe(law.getKeywordIndex()));
    }

    /** 条文编号比对时弱化「第」「条」等，便于「577」与「第五百七十七条」等形态互认。 */
    private String stripArticleNoise(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace("第", "").replace("条", "").replace("款", "").replace("项", "").replace("章", "").replace("节", "");
    }

    private String simplifyArticleKey(String articleNo) {
        return stripArticleNoise(normalize(safe(articleNo)));
    }

    private int nullSafeOrdinal(LawArticle law) {
        if (law == null || law.getArticleOrdinal() == null) {
            return Integer.MAX_VALUE;
        }
        return law.getArticleOrdinal();
    }

    private void embedArticle(LawArticle article) {
        if (!embeddingService.isEnabled()) return;
        String text = String.join("\n",
                safe(article.getLawName()), safe(article.getArticleNo()), article.getTitle(),
                safe(article.getScenarios()), safe(article.getExceptionsText()), safe(article.getCauseOfAction()));
        List<Double> vector = embeddingService.embed(text);
        if (!vector.isEmpty()) {
            article.setVectorJson(embeddingService.toJson(vector));
            article.setVectorModel(embeddingService.model());
        }
    }

    private boolean sameText(LawArticle old, LawArticle article) {
        return safe(old.getLawName()).equals(safe(article.getLawName()))
                && safe(old.getArticleNo()).equals(safe(article.getArticleNo()))
                && safe(old.getExternalRef()).equals(safe(article.getExternalRef()))
                && safe(old.getSourceUrl()).equals(safe(article.getSourceUrl()))
                && safe(old.getTitle()).equals(safe(article.getTitle()))
                && safe(old.getCategory()).equals(safe(article.getCategory()));
    }

    private void fillCreate(LawArticle article) {
        LocalDateTime now = LocalDateTime.now();
        article.setCreatedAt(now);
        article.setUpdatedAt(now);
        if (article.getSourceUpdatedAt() == null) article.setSourceUpdatedAt(now);
        if (!StringUtils.hasText(article.getStatus())) article.setStatus("现行有效");
        if (!StringUtils.hasText(article.getLevel())) article.setLevel("法律法规");
        if (!StringUtils.hasText(article.getRegion())) article.setRegion("全国");
        if (!StringUtils.hasText(article.getSourceName())) article.setSourceName(SOURCE_BUILTIN);
    }

    private void refreshLastSyncAt() {
        LawArticle last = mapper.selectOne(new LambdaQueryWrapper<LawArticle>()
                .orderByDesc(LawArticle::getSourceUpdatedAt)
                .last("limit 1"));
        lastSyncAt = last == null ? null : last.getSourceUpdatedAt();
    }

    private List<String> distinct(List<String> values) {
        Set<String> set = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) set.add(value);
        }
        return new ArrayList<>(set);
    }

    private List<LawArticle> seedArticles() {
        return List.of();
    }

    private LocalDateTime nullSafe(LocalDateTime value) {
        return value == null ? LocalDateTime.MIN : value;
    }

    private String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private List<String> tokens(String text) {
        if (!StringUtils.hasText(text)) return List.of();
        String normalized = text.trim();
        List<String> parts = new ArrayList<>();
        for (String part : normalized.split("[\\s,，;；、。]+")) {
            if (part.length() > 1) {
                parts.add(part);
            }
        }
        if (parts.isEmpty() && normalized.length() > 1) {
            parts.add(normalized);
        }
        return parts;
    }

    private boolean isCurrentStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return true;
        }
        String s = status.trim();
        return !(s.contains("废止") || s.contains("失效"));
    }

    /**
     * 详情接口：通俗解读正文仅保留「大白话」本身；适用场景、例外、免责声明、效力提示在其它字段或区块展示。
     */
    private String normalizeDetailInterpretationBody(String interpretation) {
        String core = LawInterpretationTexts.extractCoreInterpretation(safe(interpretation).trim());
        if (!StringUtils.hasText(core)) {
            return "当前条文解读信息不足，请以官方文本为准。";
        }
        return core;
    }

    /** 非现行有效等：单独返回，由前端与通俗解读正文分区展示 */
    private String buildValidityNotice(LawArticle law) {
        if (law == null || (Boolean.TRUE.equals(law.getCurrentEffective()) && isCurrentStatus(law.getStatus()))) {
            return "";
        }
        String replacement = "";
        if (law.getReplacedByArticleId() != null) {
            LawArticle replacedBy = mapper.selectById(law.getReplacedByArticleId());
            if (replacedBy != null) {
                replacement = "替代依据：" + firstNonBlank(replacedBy.getLawName(), "") + firstNonBlank(replacedBy.getArticleNo(), "") + "。";
            }
        }
        return "【效力提示】本条当前标注为「" + firstNonBlank(law.getStatus(), "非现行有效")
                + "」，版本号：" + firstNonBlank(law.getVersionNo(), "未标注") + "。"
                + (StringUtils.hasText(replacement) ? replacement : "如涉及正式适用，请以官方文本和最新修订为准。");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String clamp(String text, int max) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String s = text.trim();
        int n = Math.max(20, max);
        return s.length() <= n ? s : s.substring(0, n);
    }

    private String sha256(String text) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }

    private record ScoredLaw(LawArticle law, double score) {
    }
}
