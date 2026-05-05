package com.fatongai.legalassistant.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fatongai.legalassistant.casebase.entity.LegalCase;
import com.fatongai.legalassistant.casebase.mapper.LegalCaseMapper;
import com.fatongai.legalassistant.file.TextExtractService;
import com.fatongai.legalassistant.law.entity.LawArticle;
import com.fatongai.legalassistant.law.mapper.LawArticleMapper;
import com.fatongai.legalassistant.law.service.LawArticleExternalBodyResolver;
import com.fatongai.legalassistant.law.service.QwenEmbeddingService;
import com.fatongai.legalassistant.rag.dto.RagAnswerResponse;
import com.fatongai.legalassistant.rag.dto.RagDocumentView;
import com.fatongai.legalassistant.rag.dto.RagMetaResponse;
import com.fatongai.legalassistant.rag.dto.RagSearchResponse;
import com.fatongai.legalassistant.rag.dto.RagSearchResult;
import com.fatongai.legalassistant.rag.entity.RagDocument;
import com.fatongai.legalassistant.rag.entity.RagDocumentChunk;
import com.fatongai.legalassistant.rag.mapper.RagDocumentChunkMapper;
import com.fatongai.legalassistant.rag.mapper.RagDocumentMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class RagKnowledgeService {
    private static final int CHUNK_SIZE = 1200;
    private static final int CHUNK_OVERLAP = 160;

    private final RagDocumentMapper documentMapper;
    private final RagDocumentChunkMapper chunkMapper;
    private final QwenEmbeddingService embeddingService;
    private final TextExtractService textExtractService;
    private final LawArticleMapper lawArticleMapper;
    private final LegalCaseMapper legalCaseMapper;
    private final LawArticleExternalBodyResolver lawBodyResolver;
    private final ChatClient chatClient;

    @Value("${spring.ai.openai.api-key:}")
    private String chatApiKey;

    public RagKnowledgeService(RagDocumentMapper documentMapper,
                               RagDocumentChunkMapper chunkMapper,
                               QwenEmbeddingService embeddingService,
                               TextExtractService textExtractService,
                               LawArticleMapper lawArticleMapper,
                               LegalCaseMapper legalCaseMapper,
                               @Lazy LawArticleExternalBodyResolver lawBodyResolver,
                               ChatClient.Builder builder) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.embeddingService = embeddingService;
        this.textExtractService = textExtractService;
        this.lawArticleMapper = lawArticleMapper;
        this.legalCaseMapper = legalCaseMapper;
        this.lawBodyResolver = lawBodyResolver;
        this.chatClient = builder.build();
    }

    @Transactional
    public RagDocumentView ingestFile(MultipartFile file,
                                      String title,
                                      String sourceType,
                                      String moduleScope,
                                      String businessType,
                                      String tags) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请上传知识库文件");
        }
        String docId = "upload-" + java.util.UUID.randomUUID().toString().replace("-", "");
        String original = file.getOriginalFilename() == null ? "未命名文件" : file.getOriginalFilename();
        String safeTitle = StringUtils.hasText(title) ? title.trim() : original;
        String text;
        try (var in = file.getInputStream()) {
            text = textExtractService.extract(in, original);
        }
        return upsertText(docId, safeTitle, original, sourceType, moduleScope, businessType, tags,
                file.getContentType(), file.getSize(), text);
    }

    @Transactional
    public RagDocumentView ingestText(String title,
                                      String text,
                                      String sourceType,
                                      String moduleScope,
                                      String businessType,
                                      String tags) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("知识文本不能为空");
        }
        String docId = "text-" + java.util.UUID.randomUUID().toString().replace("-", "");
        String safeTitle = StringUtils.hasText(title) ? title.trim() : "手动录入知识";
        return upsertText(docId, safeTitle, safeTitle + ".txt", sourceType, moduleScope, businessType, tags,
                "text/plain", (long) text.length(), text);
    }

    @Transactional
    public RagDocumentView upsertText(String docId,
                                      String title,
                                      String originalName,
                                      String sourceType,
                                      String moduleScope,
                                      String businessType,
                                      String tags,
                                      String contentType,
                                      Long fileSize,
                                      String text) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("知识文本不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        RagDocument doc = documentMapper.selectOne(new LambdaQueryWrapper<RagDocument>()
                .eq(RagDocument::getDocId, docId)
                .last("limit 1"));
        if (doc == null) {
            doc = new RagDocument();
            doc.setDocId(docId);
            doc.setCreatedAt(now);
        } else {
            chunkMapper.delete(new LambdaQueryWrapper<RagDocumentChunk>().eq(RagDocumentChunk::getDocId, docId));
        }
        doc.setTitle(trim(title, 512));
        doc.setOriginalName(trim(originalName, 512));
        doc.setSourceType(defaultText(sourceType, "upload"));
        doc.setModuleScope(defaultText(moduleScope, "all"));
        doc.setBusinessType(defaultText(businessType, "通用法律知识"));
        doc.setTags(trim(tags, 1024));
        doc.setContentType(trim(contentType, 128));
        doc.setFileSize(fileSize == null ? 0L : fileSize);
        doc.setStatus("indexed");
        doc.setErrorMessage("");
        doc.setUpdatedAt(now);

        List<String> chunks = splitChunks(text);
        doc.setChunkCount(chunks.size());
        if (doc.getId() == null) {
            documentMapper.insert(doc);
        } else {
            documentMapper.updateById(doc);
        }

        int i = 1;
        for (String chunkText : chunks) {
            RagDocumentChunk chunk = new RagDocumentChunk();
            chunk.setDocId(docId);
            chunk.setChunkIndex(i);
            chunk.setTitle(doc.getTitle() + " #" + i);
            chunk.setContent(chunkText);
            chunk.setSummary(trim(chunkText.replaceAll("\\s+", " "), 300));
            chunk.setKeywords(trim(tags, 1024));
            chunk.setSourceType(doc.getSourceType());
            chunk.setModuleScope(doc.getModuleScope());
            chunk.setBusinessType(doc.getBusinessType());
            chunk.setCreatedAt(now);
            chunk.setUpdatedAt(now);
            embed(chunk);
            chunkMapper.insert(chunk);
            i++;
        }
        return RagDocumentView.of(doc);
    }

    public RagSearchResponse search(String query, String moduleScope, String businessType, Integer limit) {
        int n = limit == null || limit < 1 ? 6 : Math.min(limit, 20);
        List<RagSearchResult> results = searchTop(query, moduleScope, businessType, n);
        return new RagSearchResponse(
                query == null ? "" : query,
                StringUtils.hasText(moduleScope) ? moduleScope : "all",
                StringUtils.hasText(businessType) ? businessType : "全部",
                n,
                results.size(),
                embeddingService.isEnabled(),
                results
        );
    }

    public List<RagSearchResult> searchTop(String query, String moduleScope, String businessType, int limit) {
        LambdaQueryWrapper<RagDocumentChunk> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(moduleScope) && !"all".equalsIgnoreCase(moduleScope.trim())) {
            List<String> scopes = expandedScopes(moduleScope);
            wrapper.in(RagDocumentChunk::getModuleScope, scopes);
        }
        if (StringUtils.hasText(businessType) && !"全部".equals(businessType.trim())) {
            wrapper.eq(RagDocumentChunk::getBusinessType, businessType.trim());
        }
        List<RagDocumentChunk> chunks = chunkMapper.selectList(wrapper);
        List<Double> queryVector = StringUtils.hasText(query) ? embeddingService.embed(query) : List.of();
        Map<String, RagDocument> docs = new LinkedHashMap<>();
        return chunks.stream()
                .map(chunk -> {
                    RagDocument doc = docs.computeIfAbsent(chunk.getDocId(), this::findDoc);
                    return RagSearchResult.of(chunk, doc, score(chunk, query, queryVector));
                })
                .filter(item -> !StringUtils.hasText(query) || item.score() > 0.035)
                .sorted(Comparator.comparing(RagSearchResult::score).reversed()
                        .thenComparing(RagSearchResult::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(1, Math.min(limit, 20)))
                .toList();
    }

    public List<String> referenceLines(String query, String moduleScope, int limit) {
        return searchTop(query, moduleScope, null, limit).stream()
                .map(RagSearchResult::toReferenceLine)
                .toList();
    }

    public List<String> referenceLines(String query, String moduleScope, String businessType, int limit) {
        return searchTop(query, moduleScope, businessType, limit).stream()
                .map(RagSearchResult::toReferenceLine)
                .toList();
    }

    public String contextBlock(String query, String moduleScope, int limit) {
        return contextBlock(query, moduleScope, null, limit);
    }

    public String contextBlock(String query, String moduleScope, String businessType, int limit) {
        List<RagSearchResult> hits = searchTop(query, moduleScope, businessType, limit);
        if (hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (RagSearchResult hit : hits) {
            sb.append("资料").append(i++).append("：").append(hit.documentTitle())
                    .append(" / 片段").append(hit.chunkIndex())
                    .append(" / ").append(hit.scoreLabel()).append("\n")
                    .append(clip(hit.content(), 1200)).append("\n\n");
        }
        return sb.toString().trim();
    }

    public RagAnswerResponse answer(String question, String moduleScope, String businessType, Integer limit) {
        if (!StringUtils.hasText(question)) {
            throw new IllegalArgumentException("问题不能为空");
        }
        int n = limit == null || limit < 1 ? 6 : Math.min(limit, 12);
        List<RagSearchResult> refs = searchTop(question, moduleScope, businessType, n);
        String answer;
        boolean ai = StringUtils.hasText(chatApiKey);
        if (ai) {
            try {
                answer = chatClient.prompt()
                        .system("""
                                你是法律 RAG 知识库问答助手。必须优先依据给定知识库片段回答，并指出依据来自哪些资料。
                                若资料不足，请明确说明无法从知识库确认，并给出需要补充的材料。回答应通俗、克制、可执行，不构成律师法律意见。
                                """)
                        .user(answerPrompt(question, refs))
                        .call()
                        .content();
            } catch (Exception ex) {
                answer = localAnswer(question, refs);
                ai = false;
            }
        } else {
            answer = localAnswer(question, refs);
        }
        return new RagAnswerResponse(question, answer, refs, ai, LocalDateTime.now());
    }

    public List<RagDocumentView> listDocuments(String moduleScope, String status, Integer page, Integer size) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 100);
        LambdaQueryWrapper<RagDocument> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(moduleScope) && !"all".equalsIgnoreCase(moduleScope.trim())) {
            wrapper.eq(RagDocument::getModuleScope, moduleScope.trim());
        }
        if (StringUtils.hasText(status) && !"全部".equals(status.trim())) {
            wrapper.eq(RagDocument::getStatus, status.trim());
        }
        wrapper.orderByDesc(RagDocument::getUpdatedAt).last("limit " + ((p - 1) * s) + "," + s);
        return documentMapper.selectList(wrapper).stream().map(RagDocumentView::of).toList();
    }

    public RagMetaResponse meta() {
        List<RagDocument> docs = documentMapper.selectList(new QueryWrapper<RagDocument>().select(
                "module_scope", "business_type", "status", "updated_at"
        ));
        Map<String, Long> statusStats = new LinkedHashMap<>();
        for (RagDocument doc : docs) {
            String st = StringUtils.hasText(doc.getStatus()) ? doc.getStatus() : "unknown";
            statusStats.put(st, statusStats.getOrDefault(st, 0L) + 1);
        }
        LocalDateTime last = docs.stream()
                .map(RagDocument::getUpdatedAt)
                .filter(v -> v != null)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        return new RagMetaResponse(
                documentMapper.selectCount(null),
                chunkMapper.selectCount(null),
                embeddingService.isEnabled(),
                embeddingService.model(),
                last,
                distinct(docs.stream().map(RagDocument::getModuleScope).toList()),
                distinct(docs.stream().map(RagDocument::getBusinessType).toList()),
                statusStats
        );
    }

    @Transactional
    public Map<String, Integer> bootstrapFoundation() {
        int laws = 0;
        int cases = 0;
        for (LawArticle law : lawArticleMapper.selectList(new LambdaQueryWrapper<LawArticle>().last("limit 500"))) {
            String slice = lawBodyResolver.resolveArticleSlice(law, 12000);
            String text = String.join("\n",
                    safe(law.getLawName()) + safe(law.getArticleNo()),
                    safe(law.getTitle()),
                    safe(slice),
                    safe(law.getInterpretation()),
                    safe(law.getScenarios()),
                    safe(law.getExceptionsText()));
            if (StringUtils.hasText(text)) {
                upsertText("law-" + law.getId(), law.getLawName() + " " + law.getArticleNo(), law.getLawName(),
                        "law", "law", safe(law.getCauseOfAction()), law.getCategory(), "text/plain",
                        (long) text.length(), text);
                laws++;
            }
        }
        for (LegalCase c : legalCaseMapper.selectList(new LambdaQueryWrapper<LegalCase>().last("limit 500"))) {
            String text = String.join("\n",
                    safe(c.getTitle()),
                    safe(c.getFacts()),
                    safe(c.getAdjudicationPoints()),
                    safe(c.getJudgmentResult()),
                    safe(c.getKeyEvidence()));
            if (StringUtils.hasText(text)) {
                upsertText("case-" + c.getId(), c.getTitle(), c.getCaseNo(),
                        "case", "case", safe(c.getCause()), c.getRegion(), "text/plain",
                        (long) text.length(), text);
                cases++;
            }
        }
        return Map.of("laws", laws, "cases", cases);
    }

    @Transactional
    public void indexLawArticle(LawArticle law) {
        if (law == null || law.getId() == null) {
            return;
        }
        String slice = lawBodyResolver.resolveArticleSlice(law, 12000);
        String text = String.join("\n",
                safe(law.getLawName()) + safe(law.getArticleNo()),
                safe(law.getTitle()),
                safe(slice),
                safe(law.getInterpretation()),
                safe(law.getScenarios()),
                safe(law.getExceptionsText()),
                safe(law.getCauseOfAction()));
        if (!StringUtils.hasText(text)) {
            return;
        }
        upsertText("law-" + law.getId(), safe(law.getLawName()) + " " + safe(law.getArticleNo()), safe(law.getLawName()),
                "law", "law", defaultText(law.getCauseOfAction(), "法律法规"), law.getCategory(), "text/plain",
                (long) text.length(), text);
    }

    @Transactional
    public void indexLegalCase(LegalCase legalCase) {
        if (legalCase == null || legalCase.getId() == null) {
            return;
        }
        String text = String.join("\n",
                safe(legalCase.getTitle()),
                safe(legalCase.getFacts()),
                safe(legalCase.getAdjudicationPoints()),
                safe(legalCase.getJudgmentResult()),
                safe(legalCase.getKeyEvidence()),
                safe(legalCase.getCause()));
        if (!StringUtils.hasText(text)) {
            return;
        }
        upsertText("case-" + legalCase.getId(), safe(legalCase.getTitle()), safe(legalCase.getCaseNo()),
                "case", "case", defaultText(legalCase.getCause(), "案例"), legalCase.getRegion(), "text/plain",
                (long) text.length(), text);
    }

    @Transactional
    public int deleteDocument(String docId) {
        if (!StringUtils.hasText(docId)) {
            return 0;
        }
        chunkMapper.delete(new LambdaQueryWrapper<RagDocumentChunk>().eq(RagDocumentChunk::getDocId, docId.trim()));
        return documentMapper.delete(new LambdaQueryWrapper<RagDocument>().eq(RagDocument::getDocId, docId.trim()));
    }

    private void embed(RagDocumentChunk chunk) {
        if (!embeddingService.isEnabled()) return;
        List<Double> vector = embeddingService.embed(String.join("\n",
                safe(chunk.getTitle()), safe(chunk.getContent()), safe(chunk.getKeywords())));
        if (!vector.isEmpty()) {
            chunk.setVectorJson(embeddingService.toJson(vector));
            chunk.setVectorModel(embeddingService.model());
        }
    }

    private double score(RagDocumentChunk chunk, String query, List<Double> queryVector) {
        if (!StringUtils.hasText(query)) return 0.5;
        String q = normalize(query);
        String hay = normalize(String.join(" ",
                safe(chunk.getTitle()), safe(chunk.getContent()), safe(chunk.getSummary()),
                safe(chunk.getKeywords()), safe(chunk.getBusinessType())));
        double lexical = hay.contains(q) ? 0.42 : 0;
        for (String token : tokens(query)) {
            String t = normalize(token);
            if (t.length() >= 2 && hay.contains(t)) lexical += 0.08;
        }
        double vector = 0;
        if (!queryVector.isEmpty() && StringUtils.hasText(chunk.getVectorJson())) {
            vector = embeddingService.cosine(queryVector, embeddingService.fromJson(chunk.getVectorJson()));
        }
        return Math.min(0.99, lexical + vector * 0.62);
    }

    private List<String> expandedScopes(String moduleScope) {
        String scope = moduleScope == null ? "" : moduleScope.trim().toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(scope) || "all".equals(scope)) {
            return List.of("all");
        }
        LinkedHashSet<String> scopes = new LinkedHashSet<>();
        scopes.add(scope);
        switch (scope) {
            case "law" -> scopes.add("all");
            case "case" -> {
                scopes.add("law");
                scopes.add("all");
            }
            case "chat", "document", "contract", "guide" -> {
                scopes.add("law");
                scopes.add("case");
                scopes.add("all");
            }
            default -> scopes.add("all");
        }
        return new ArrayList<>(scopes);
    }

    private List<String> splitChunks(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        List<String> out = new ArrayList<>();
        if (normalized.length() <= CHUNK_SIZE) {
            out.add(normalized);
            return out;
        }
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + CHUNK_SIZE);
            int preferred = normalized.lastIndexOf("\n\n", end);
            if (preferred <= start + 300) {
                preferred = normalized.lastIndexOf("\n", end);
            }
            if (preferred > start + 300) {
                end = preferred;
            }
            String part = normalized.substring(start, end).trim();
            if (!part.isBlank()) out.add(part);
            if (end >= normalized.length()) break;
            start = Math.max(end - CHUNK_OVERLAP, start + 1);
        }
        return out;
    }

    private String answerPrompt(String question, List<RagSearchResult> refs) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户问题：").append(question).append("\n\n知识库片段：\n");
        int i = 1;
        for (RagSearchResult ref : refs) {
            sb.append("[").append(i++).append("] ")
                    .append(ref.documentTitle()).append(" / 片段").append(ref.chunkIndex()).append("\n")
                    .append(clip(ref.content(), 1500)).append("\n\n");
        }
        if (refs.isEmpty()) {
            sb.append("未检索到相关片段。\n");
        }
        return sb.toString();
    }

    private String localAnswer(String question, List<RagSearchResult> refs) {
        if (refs.isEmpty()) {
            return "知识库中暂未检索到可直接支撑该问题的资料。建议先上传相关法律法规、合同模板、案例或内部制度后再提问。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("已从知识库检索到 ").append(refs.size()).append(" 条参考资料。可先依据以下片段判断：\n\n");
        int i = 1;
        for (RagSearchResult ref : refs) {
            sb.append(i++).append(". ").append(ref.documentTitle()).append("：")
                    .append(clip(ref.content().replaceAll("\\s+", " "), 220)).append("\n");
        }
        sb.append("\n以上为知识库检索摘要，具体结论还需结合完整事实和证据核对。");
        return sb.toString();
    }

    private RagDocument findDoc(String docId) {
        if (!StringUtils.hasText(docId)) return null;
        return documentMapper.selectOne(new LambdaQueryWrapper<RagDocument>()
                .eq(RagDocument::getDocId, docId)
                .last("limit 1"));
    }

    private List<String> distinct(List<String> values) {
        Set<String> set = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) set.add(value);
        }
        return new ArrayList<>(set);
    }

    private List<String> tokens(String text) {
        if (!StringUtils.hasText(text)) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : text.trim().split("[\\s,，;；、。]+")) {
            if (part.length() >= 2) out.add(part);
        }
        String[] hot = {"劳动", "辞退", "补偿", "欠薪", "工伤", "离婚", "抚养", "退款", "欺诈", "合同", "违约", "押金", "借条", "利息", "仲裁", "起诉"};
        String q = normalize(text);
        for (String word : hot) {
            if (q.contains(word)) out.add(word);
        }
        return out;
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private String defaultText(String text, String fallback) {
        return StringUtils.hasText(text) ? trim(text.trim(), 128) : fallback;
    }

    private String trim(String text, int max) {
        if (text == null) return "";
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private String clip(String text, int max) {
        if (text == null) return "";
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }
}
