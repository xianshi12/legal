package com.fatongai.legalassistant.law.service;

import com.fatongai.legalassistant.law.entity.LawArticle;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 法条正文「轻量映射」解析：库内仅存 {@link LawArticle#getExternalRef()} / {@link LawArticle#getSourceUrl()}，
 * 全文在展示或解读时从国家库或来源页拉取，不写入数据库。
 */
@Service
public class LawArticleExternalBodyResolver {

    private static final Logger log = LoggerFactory.getLogger(LawArticleExternalBodyResolver.class);
    private static final String NPC_PREFIX = "npc:";
    private static final long NPC_CACHE_TTL_MS = 10 * 60 * 1000L;

    private final NpcFlkLawImporter npcFlkLawImporter;

    @Value("${app.law.npc-flk.read-timeout-ms:120000}")
    private int httpReadTimeoutMs;

    private final ConcurrentHashMap<String, NpcPlainCache> npcPlainByBbbs = new ConcurrentHashMap<>();

    public LawArticleExternalBodyResolver(NpcFlkLawImporter npcFlkLawImporter) {
        this.npcFlkLawImporter = npcFlkLawImporter;
    }

    /**
     * 解析整部法规合并正文（未按条切分），用于截取单条或向量/检索补充。
     */
    public String resolveFullDocumentPlain(LawArticle law) {
        if (law == null) {
            return "";
        }
        String ref = law.getExternalRef();
        if (StringUtils.hasText(ref) && ref.startsWith(NPC_PREFIX)) {
            String bbbs = ref.substring(NPC_PREFIX.length()).trim();
            return fetchNpcPlainCached(bbbs);
        }
        String url = law.getSourceUrl();
        if (StringUtils.hasText(url) && isHttpUrl(url) && !url.startsWith(LawArticleService.SOURCE_URL_SEED_PREFIX)) {
            return fetchHttpPagePlain(url.trim());
        }
        return safe(law.getContent());
    }

    /**
     * 按条号从映射正文中截取本条，供列表/详情/通俗解读使用。
     */
    public String resolveArticleSlice(LawArticle law, int maxChars) {
        String full = resolveFullDocumentPlain(law);
        if (!StringUtils.hasText(full)) {
            return "";
        }
        return LawArticleBodyExtractor.extractArticleBody(full, law.getArticleNo(), maxChars);
    }

    private String fetchNpcPlainCached(String bbbs) {
        if (!StringUtils.hasText(bbbs)) {
            return "";
        }
        NpcPlainCache hit = npcPlainByBbbs.get(bbbs);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.ts < NPC_CACHE_TTL_MS && StringUtils.hasText(hit.text)) {
            return hit.text;
        }
        String t = npcFlkLawImporter.fetchNpcMergedPlainText(bbbs);
        npcPlainByBbbs.put(bbbs, new NpcPlainCache(t == null ? "" : t, now));
        return t == null ? "" : t;
    }

    private String fetchHttpPagePlain(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 LegalAssistantLawBodyResolver/1.0")
                    .timeout(Math.max(5000, httpReadTimeoutMs))
                    .followRedirects(true)
                    .get();
            return extractMainPlain(doc);
        } catch (Exception e) {
            log.debug("法条映射 HTTP 正文拉取失败 url={}: {}", url, e.getMessage());
            return "";
        }
    }

    private static String extractMainPlain(Document doc) {
        String[] selectors = {"article", "main", ".detail-content", ".content", ".article-content", ".law-content", "#content", "body"};
        for (String sel : selectors) {
            Element el = doc.selectFirst(sel);
            if (el != null && StringUtils.hasText(el.text())) {
                return el.text().trim();
            }
        }
        return "";
    }

    private static boolean isHttpUrl(String url) {
        String u = url.toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private record NpcPlainCache(String text, long ts) {
    }
}
