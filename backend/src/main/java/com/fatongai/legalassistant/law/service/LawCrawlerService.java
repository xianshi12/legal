package com.fatongai.legalassistant.law.service;

import com.fatongai.legalassistant.law.dto.LawSyncResult;
import com.fatongai.legalassistant.law.entity.LawArticle;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LawCrawlerService {
    private static final Logger log = LoggerFactory.getLogger(LawCrawlerService.class);
    private static final Pattern ARTICLE_PATTERN = Pattern.compile(
            "(第\\s*[一二三四五六七八九十百千万零〇两0-9]+\\s*条(?:之一|之二|之三|之四|之五)?)");

    private final LawArticleService lawArticleService;
    private final boolean enabled;
    private final List<String> seedUrls;
    private final int timeoutMs;
    /** 若该 URL 已在 law_article.source_url 下有条目，则跳过网络抓取。 */
    private final boolean skipIfPresent;

    public LawCrawlerService(LawArticleService lawArticleService,
                             @Value("${app.law.crawler.enabled:true}") boolean enabled,
                             @Value("${app.law.crawler.seed-urls:}") String seedUrls,
                             @Value("${app.law.crawler.read-timeout-ms:20000}") int timeoutMs,
                             @Value("${app.law.crawler.skip-if-present:true}") boolean skipIfPresent) {
        this.lawArticleService = lawArticleService;
        this.enabled = enabled;
        this.seedUrls = parseUrls(seedUrls);
        this.timeoutMs = timeoutMs;
        this.skipIfPresent = skipIfPresent;
    }

    @Scheduled(cron = "${app.law.crawler.cron:0 0 3 * * ?}")
    public void scheduledSync() {
        if (enabled && !seedUrls.isEmpty()) {
            sync(seedUrls, false);
        }
    }

    /**
     * 将整篇法规正文拆成多条 {@link LawArticle}（按「第…条」），供页面抓取与 NPC 国家库同步复用。
     */
    public List<LawArticle> parsePlainTextIntoArticles(String lawName, String plainText, String detailPageUrl) {
        String location = "flk.npc.gov.cn";
        try {
            String host = java.net.URI.create(detailPageUrl.replace(" ", "%20")).getHost();
            if (StringUtils.hasText(host)) {
                location = host;
            }
        } catch (Exception ignored) {
            // keep default
        }
        return splitArticles(cleanTitle(lawName), normalizePlainText(plainText), detailPageUrl, location);
    }

    public LawSyncResult sync(List<String> urls) {
        return sync(urls, false);
    }

    public LawSyncResult sync(List<String> urls, boolean forceResync) {
        List<String> targets = urls == null || urls.isEmpty() ? seedUrls : urls.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        boolean doSkip = skipIfPresent && !forceResync;
        log.info("法条URL同步开始 urls={} timeoutMs={} skipIfPresent={} forceResync={}",
                targets.size(), timeoutMs, doSkip, forceResync);
        List<String> failed = new ArrayList<>();
        List<LawArticle> parsed = new ArrayList<>();
        int fetched = 0;
        int skipped = 0;
        long t0 = System.nanoTime();
        for (String url : targets) {
            try {
                if (doSkip && lawArticleService.hasSplitArticlesForSourceUrl(url)) {
                    int removed = lawArticleService.deleteNpcFullTextPlaceholders("", url);
                    if (removed > 0) {
                        log.info("法条URL已按条入库，清理旧全文占位后跳过 url={} removed={}", url, removed);
                    }
                    skipped++;
                    log.info("法条URL已按条拆分入库，跳过 url={}", url);
                    continue;
                }
                long t1 = System.nanoTime();
                List<LawArticle> chunk = fetchLawPage(url);
                if (chunk.size() > 1) {
                    int removed = lawArticleService.deleteNpcFullTextPlaceholders("", url);
                    if (removed > 0) {
                        log.info("法条URL已拆分为按条记录，清理旧全文占位 url={} removed={}", url, removed);
                    }
                }
                parsed.addAll(chunk);
                fetched++;
                log.info("法条URL抓取完成 url={} articles={} 耗时={}ms", url, chunk.size(), (System.nanoTime() - t1) / 1_000_000L);
            } catch (Exception e) {
                log.warn("法条URL抓取失败 url={}: {}", url, e.getMessage(), e);
                failed.add(url + "（" + e.getMessage() + "）");
            }
        }
        int upserted = lawArticleService.upsertAll(parsed);
        int embedded = lawArticleService.updateMissingEmbeddings(Math.max(20, upserted));
        log.info("法条URL同步结束 fetched={} skipped={} upserted={} embedded={} failed={} 总耗时={}ms",
                fetched, skipped, upserted, embedded, failed.size(), (System.nanoTime() - t0) / 1_000_000L);
        return new LawSyncResult(fetched, upserted, embedded, skipped, failed, LocalDateTime.now());
    }

    private List<LawArticle> fetchLawPage(String url) throws Exception {
        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 LegalAssistantBot/1.0")
                .timeout(timeoutMs)
                .get();
        String lawName = firstText(doc, "h1", ".title", "title");
        if (!StringUtils.hasText(lawName)) {
            lawName = doc.title();
        }
        String mainText = firstText(doc, "article", "main", ".content", ".article-content", ".law-content", "body");
        if (!StringUtils.hasText(mainText)) {
            return List.of();
        }
        return splitArticles(cleanTitle(lawName), normalizePlainText(mainText), url, doc.location());
    }

    private List<LawArticle> splitArticles(String lawName, String text, String sourceUrl, String location) {
        List<LawArticle> list = new ArrayList<>();
        String bodyText = LawArticleBodyExtractor.trimLeadingBeforeFirstArticleHeading(text);
        Matcher matcher = ARTICLE_PATTERN.matcher(bodyText);
        List<Integer> starts = new ArrayList<>();
        List<String> articleNos = new ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start());
            articleNos.add(matcher.group(1));
        }
        if (starts.isEmpty()) {
            return list;
        }
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1) : bodyText.length();
            String articleText = bodyText.substring(start, end).trim();
            if (articleText.length() < 12) continue;
            list.add(fromPage(lawName, articleNos.get(i), articleText, sourceUrl, location));
        }
        return list;
    }

    private LawArticle fromPage(String lawName, String articleNo, String content, String sourceUrl, String location) {
        String no = articleNo == null ? "" : articleNo.replaceAll("\\s+", "").trim();
        String clean = LawArticleDigestHelper.stripTechnicalNoise(content);
        LawArticle law = new LawArticle();
        law.setLawName(lawName);
        law.setArticleNo(no);
        law.setTitle("《" + lawName + "》" + no);
        law.setArticleSummary(LawArticleBodyExtractor.clamp(clean, 480));
        law.setInterpretationSummary(LawArticleBodyExtractor.clamp(
                LawArticleDigestHelper.colloquialInterpretation(lawName, no, clean), 500));
        law.setContent(clean);
        law.setInterpretation(null);
        law.setVectorJson(null);
        law.setVectorModel(null);
        law.setExternalRef(StringUtils.hasText(sourceUrl) ? sourceUrl.trim() : null);
        law.setContentRef(StringUtils.hasText(sourceUrl) ? "url:" + sourceUrl.trim() : null);
        law.setSourceName(sourceName(location));
        law.setSourceUrl(sourceUrl);
        law.setScenarios(LawArticleDigestHelper.inferScenarios(lawName, clean));
        law.setExceptionsText(LawArticleDigestHelper.inferExceptionsText(lawName, clean));
        law.setCauseOfAction(LawArticleDigestHelper.inferCauseOfAction(lawName, clean));
        law.setCategory(inferCategory(lawName, clean));
        law.setLevel("法律法规");
        law.setRegion("全国");
        law.setStatus("现行有效");
        law.setSourceUpdatedAt(LocalDateTime.now());
        return law;
    }

    private String inferCategory(String lawName, String content) {
        String text = lawName + content;
        if (text.contains("劳动") || text.contains("工伤")) return "劳动法";
        if (text.contains("刑法") || text.contains("犯罪")) return "刑法";
        if (text.contains("民法") || text.contains("婚姻") || text.contains("继承") || text.contains("物权")
                || text.contains("合同") || text.contains("侵权") || text.contains("借款")) return "民法";
        if (text.contains("行政")) return "行政法";
        if (text.contains("诉讼") || text.contains("人民法院") || text.contains("司法解释")) return "司法解释";
        return "综合";
    }

    private String normalizePlainText(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.replace('\u00A0', ' ')
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String firstText(Document doc, String... selectors) {
        for (String selector : selectors) {
            Element el = doc.selectFirst(selector);
            if (el != null && StringUtils.hasText(el.text())) {
                return el.text().trim();
            }
        }
        return "";
    }

    private String cleanTitle(String title) {
        String t = title == null ? "" : title.trim();
        return t.replaceAll("[_-].*$", "").replace("法律法规库", "").trim();
    }

    private String sourceName(String location) {
        if (!StringUtils.hasText(location)) return "第三方法律库";
        return location.replaceFirst("^https?://", "").replaceFirst("/.*$", "");
    }

    private List<String> parseUrls(String urls) {
        if (!StringUtils.hasText(urls)) return List.of();
        return Arrays.stream(urls.split("[,;\\n]+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }
}
