package com.fatongai.legalassistant.law.service;

import com.fatongai.legalassistant.law.entity.LawArticle;
import org.jsoup.Jsoup;
import org.springframework.context.annotation.Lazy;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 先尽量从 {@code sourceUrl} 拉取页面正文，再截取目标条号对应的条文片段，最后生成通俗解读（模型可选，失败回退规则摘要）。
 * 启用大模型且 {@code ai-two-step} 时：第一轮让模型通读原文并提炼要点，第二轮仅据要点写精炼大白话，避免单轮长文时漏读或啰嗦。
 */
@Service
public class LawColloquialInterpretationService {

    private static final Logger log = LoggerFactory.getLogger(LawColloquialInterpretationService.class);
    /** 终稿大白话允许的最大字数（精炼简短） */
    private static final int MAX_FINAL_INTERPRETATION_CHARS = 900;
    private static final String SEED_URL_PREFIX = "seed://";
    private static final String UA = "Mozilla/5.0 LegalAssistantLawArticle/1.0";

    private final ChatClient chatClient;
    private final LawArticleExternalBodyResolver bodyResolver;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${app.law.interpretation.read-timeout-ms:20000}")
    private int readTimeoutMs;

    @Value("${app.law.interpretation.max-article-chars:4800}")
    private int maxArticleChars;

    @Value("${app.law.interpretation.ai-enabled:true}")
    private boolean aiEnabled;

    @Value("${app.law.interpretation.ai-two-step:true}")
    private boolean aiTwoStep;

    @Value("${app.law.interpretation.ai-two-step-min-chars:80}")
    private int aiTwoStepMinChars;

    public LawColloquialInterpretationService(ChatClient.Builder builder,
                                            @Lazy LawArticleExternalBodyResolver bodyResolver) {
        this.chatClient = builder.build();
        this.bodyResolver = bodyResolver;
    }

    /**
     * @param fetchSourceUrl 为 true 且来源为 http(s) 时尝试抓取页面，与库内正文择优作为截取源
     * @param pageCache      同一详情 URL 多法条时复用抓取结果，可为 null
     * @param useAiLayer     是否调用大模型；入库同步建议 false，仅截取+规则，避免批量打满配额
     */
    public String buildInterpretation(LawArticle article,
                                      boolean fetchSourceUrl,
                                      Map<String, String> pageCache,
                                      boolean useAiLayer) {
        String merged = bodyResolver.resolveFullDocumentPlain(article);
        LawArticle work = article;
        if (StringUtils.hasText(merged)) {
            work = new LawArticle();
            work.setId(article.getId());
            work.setLawName(article.getLawName());
            work.setArticleNo(article.getArticleNo());
            work.setTitle(article.getTitle());
            work.setContent(merged);
            work.setSourceUrl(article.getSourceUrl());
            work.setExternalRef(article.getExternalRef());
        }
        String base = LawArticleDigestHelper.stripTechnicalNoise(resolveBasePlainText(work, fetchSourceUrl, pageCache));
        String body = LawArticleBodyExtractor.extractArticleBody(base, work.getArticleNo(), maxArticleChars);
        if (!StringUtils.hasText(body)) {
            body = LawArticleDigestHelper.stripTechnicalNoise(
                    LawArticleBodyExtractor.normalizePlainText(work.getContent()));
        }
        String effective = LawArticleDigestHelper.effectiveLegalContent(body);
        return interpretPlainBody(work.getLawName(), work.getArticleNo(),
                StringUtils.hasText(effective) ? effective : body, useAiLayer && aiEnabled);
    }

    public String buildInterpretationFromBody(LawArticle article, String articleBody, boolean useAiLayer) {
        String body = LawArticleDigestHelper.stripTechnicalNoise(articleBody);
        String effective = LawArticleDigestHelper.effectiveLegalContent(body);
        return interpretPlainBody(article.getLawName(), article.getArticleNo(),
                StringUtils.hasText(effective) ? effective : body, useAiLayer && aiEnabled);
    }

    private String resolveBasePlainText(LawArticle article, boolean fetchSourceUrl, Map<String, String> pageCache) {
        String stored = LawArticleBodyExtractor.normalizePlainText(article.getContent());
        if (!fetchSourceUrl || !StringUtils.hasText(article.getSourceUrl())) {
            return stored;
        }
        String url = article.getSourceUrl().trim();
        if (!isHttpUrl(url) || url.startsWith(SEED_URL_PREFIX)) {
            return stored;
        }
        String page = LawArticleBodyExtractor.normalizePlainText(getOrFetchPlain(url, pageCache));
        if (!StringUtils.hasText(page)) {
            return stored;
        }
        String sliceP = LawArticleBodyExtractor.extractArticleBody(page, article.getArticleNo(), maxArticleChars);
        String sliceS = LawArticleBodyExtractor.extractArticleBody(stored, article.getArticleNo(), maxArticleChars);
        boolean pageSliceRich = sliceP.length() >= 28;
        boolean storedSliceRich = sliceS.length() >= 28;
        if (pageSliceRich && (!storedSliceRich || sliceP.length() + 20 >= sliceS.length())) {
            return page;
        }
        if (storedSliceRich) {
            return stored;
        }
        return page.length() >= stored.length() ? page : stored;
    }

    private String getOrFetchPlain(String url, Map<String, String> pageCache) {
        if (pageCache != null) {
            String hit = pageCache.get(url);
            if (hit != null) {
                return hit;
            }
        }
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(UA)
                    .timeout(Math.max(3000, readTimeoutMs))
                    .followRedirects(true)
                    .get();
            String plain = extractMainPlain(doc);
            if (pageCache != null) {
                pageCache.put(url, plain);
            }
            return plain;
        } catch (Exception e) {
            log.debug("法条来源页抓取失败 url={}: {}", url, e.getMessage());
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

    private String interpretPlainBody(String lawName, String articleNo, String body, boolean useAi) {
        String trimmed = LawArticleDigestHelper.stripTechnicalNoise(LawArticleBodyExtractor.clamp(body, maxArticleChars));
        if (!useAi || !StringUtils.hasText(apiKey)) {
            return LawArticleDigestHelper.colloquialInterpretation(lawName, articleNo, trimmed);
        }
        int minTwo = Math.max(20, aiTwoStepMinChars);
        if (aiTwoStep && trimmed.length() >= minTwo) {
            try {
                String digest = aiReadOriginalAndDigest(lawName, articleNo, trimmed);
                if (StringUtils.hasText(digest) && digest.trim().length() >= 12) {
                    String plain = aiGenerateConcisePlainLanguage(lawName, articleNo, digest.trim());
                    if (StringUtils.hasText(plain) && plain.trim().length() >= 16) {
                        return sanitizeAiOutput(plain.trim(), MAX_FINAL_INTERPRETATION_CHARS);
                    }
                }
                log.debug("法条通俗解读两阶段未得到满意终稿，回退单轮或规则");
            } catch (Exception e) {
                log.warn("法条通俗解读两阶段模型失败: {}", e.getMessage());
            }
        }
        return interpretPlainBodySinglePass(lawName, articleNo, trimmed);
    }

    /**
     * 第一轮：把条文原文交给模型通读，只输出要点列表（供第二轮写大白话，不给最终用户直接看）。
     */
    private String aiReadOriginalAndDigest(String lawName, String articleNo, String originalText) {
        String sys = """
                你是严谨的中国法律文本阅读助手。下面给出的是「条号」所对应的那一条（或紧邻的一小段）的正文，可能含款、项、序号或少量页面噪声；不要把它当成整部法规来概括，也不要混入其他条号的内容。
                请先通读该段全文，不要编造条文没有的内容。
                你的输出只用于下一步写作，因此不要写「大白话」终稿、不要劝世、不要重复法规全称超过一次。
                请输出「阅读摘要」：
                - 用 3～10 条，每条单独一行，行首用「-」。
                - 每条只写你从原文读到的：谁/什么主体、在什么条件下、能做什么或必须做什么或不得做什么、以及相应的法律效果（如无效、处罚、赔偿、解除、不承担责任等）。
                - 忽略立法目的套话、目录、纯章节标题、与具体权利义务无关的旁白。
                - 若几乎读不到实质规范，只输出一行：- 未识别到具体权利义务或法律后果。""";
        String user = """
                法规名称：《%s》
                条号：%s

                条文原文（请通读后再写摘要）：
                %s
                """.formatted(safe(lawName), safe(articleNo), originalText);
        String raw = chatClient.prompt()
                .system(sys)
                .user(user)
                .call()
                .content();
        return raw == null ? "" : raw.trim();
    }

    /**
     * 第二轮：只根据阅读摘要，写 2～4 句精炼大白话给法律小白看。
     */
    private String aiGenerateConcisePlainLanguage(String lawName, String articleNo, String readingDigest) {
        String clampedDigest = readingDigest.length() > 2600 ? readingDigest.substring(0, 2600) + "…" : readingDigest;
        String sys = """
                你是面向法律小白的普法作者。下面「阅读摘要」已由另一位助手通读法规原文后提炼，你未见到全文，请只依据摘要写作。
                任务：写出给读者看的「通俗大白话解读」终稿。
                要求：用 2～4 个短段，段与段之间空一行；口语化、精炼；标点规范（陈述句用句号，疑问用问号）；不要输出「要点」「适用」「例外」「风险提示」等标签或类似结构。
                对每段内最关键的 1 处用语（最多全文 3 处），用 Unicode 白方头括号 ⟦ 与 ⟧ 包裹以示强调，例如：⟦申请费⟧。
                不要使用 Markdown、星号、项目符号、序号；不要加「大白话」「解读」等套话。
                若摘要明确写未识别到实质规范，只输出一句：当前片段信息不足，建议打开来源页面核对具体条款。""";
        String user = """
                法规名称：《%s》
                条号：%s

                阅读摘要（模型已读原文后提炼）：
                %s
                """.formatted(safe(lawName), safe(articleNo), clampedDigest);
        String raw = chatClient.prompt()
                .system(sys)
                .user(user)
                .call()
                .content();
        return raw == null ? "" : raw.trim();
    }

    /** 单轮：原文 + 指令一步生成（短文本或两阶段失败时使用）。 */
    private String interpretPlainBodySinglePass(String lawName, String articleNo, String trimmed) {
        try {
            String sys = """
                    你是面向法律小白的中文法律普及写作助手。用户已给出法规名与「条号」，以及该条号对应的核心法律正文片段；请只解读这一条，不要泛谈整部法其他条文。
                    请只解读真正有法律效果的内容：权利、义务、禁止事项、适用条件、例外、程序、期限、责任或赔偿后果。
                    忽略“为了……制定本法”、立法背景、目录、题注、沿革说明、章节标题和网页旁白。
                    用 2～4 个短段说明：这条在管什么、谁要做什么或不能做什么、违反或符合条件会有什么后果；必要时举一个贴近日常的例子。段间空一行；标点规范。
                    不要输出「要点」「适用」「例外」「风险提示」等标签。对每段最关键的 1 处用语（全文最多 3 处）用 ⟦ ⟧ 包裹以示强调。
                    若材料没有可解读的有效法律内容，只输出：当前片段未识别到具体权利义务或法律后果，建议打开来源页面定位具体条款。
                    输出为中文正文，不要使用 Markdown、项目符号，不要加「大白话」等套话。""";
            String user = """
                    法规名称：《%s》
                    条号：%s

                    核心法律内容：
                    %s
                    """.formatted(safe(lawName), safe(articleNo), trimmed);

            String raw = chatClient.prompt()
                    .system(sys)
                    .user(user)
                    .call()
                    .content();
            if (StringUtils.hasText(raw) && raw.trim().length() >= 20) {
                return sanitizeAiOutput(raw.trim(), MAX_FINAL_INTERPRETATION_CHARS);
            }
        } catch (Exception e) {
            log.warn("法条通俗解读模型调用失败: {}", e.getMessage());
        }
        return LawArticleDigestHelper.colloquialInterpretation(lawName, articleNo, trimmed);
    }

    private static String sanitizeAiOutput(String s, int maxLen) {
        String t = s.replaceAll("[#*_`~]+", "");
        t = t.replaceFirst("^要点[:：]\\s*", "");
        if (t.length() > maxLen) {
            return t.substring(0, maxLen) + "…";
        }
        return t;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
