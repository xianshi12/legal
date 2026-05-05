package com.fatongai.legalassistant.contract.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatongai.legalassistant.contract.dto.ContractReviewResponse;
import com.fatongai.legalassistant.contract.dto.ContractRevisionItem;
import com.fatongai.legalassistant.contract.dto.ContractRiskItem;
import com.fatongai.legalassistant.rag.service.RagKnowledgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class QwenContractReviewService {
    private static final Logger log = LoggerFactory.getLogger(QwenContractReviewService.class);
    private static final String DELIMITER = "<<<CONTRACT_REVIEW_JSON>>>";

    private final RestClient restClient = RestClient.builder().build();
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    /** 仅合同审查请求使用的采样参数（与其它 Qwen 调用隔离） */
    private final double contractReviewTemperature;
    private final double contractReviewTopP;
    private final String contractReviewSeed;
    private final RagKnowledgeService ragKnowledgeService;

    public QwenContractReviewService(ObjectMapper objectMapper,
                                     @Value("${app.ai.qwen.api-key:}") String apiKey,
                                     @Value("${app.ai.qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
                                     @Value("${app.ai.qwen.model:qwen3.6-plus}") String model,
                                     @Value("${app.ai.qwen.contract-review.temperature:0.1}") double contractReviewTemperature,
                                     @Value("${app.ai.qwen.contract-review.top-p:0.35}") double contractReviewTopP,
                                     @Value("${app.ai.qwen.contract-review.seed:}") String contractReviewSeed,
                                     RagKnowledgeService ragKnowledgeService) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.baseUrl = trimSlash(baseUrl);
        this.model = model;
        this.contractReviewTemperature = contractReviewTemperature;
        this.contractReviewTopP = contractReviewTopP;
        this.contractReviewSeed = contractReviewSeed;
        this.ragKnowledgeService = ragKnowledgeService;
    }

    public ContractReviewResponse review(String contractName,
                                         String contractType,
                                         String reviewMode,
                                         String reviewFocus,
                                         String contractText) {
        if (!StringUtils.hasText(contractText)) {
            throw new IllegalArgumentException("合同正文不能为空");
        }
        String safeName = StringUtils.hasText(contractName) ? contractName : "未命名合同";
        String safeType = StringUtils.hasText(contractType) ? contractType : "通用合同";
        String safeMode = StringUtils.hasText(reviewMode) ? reviewMode : "full";
        log.info("[contract-review] 进入审查: 合同={}, 类型={}, 模式={}, 正文约 {} 字", safeName, safeType, safeMode, contractText.length());
        String ragQuery = String.join("\n", safeName, safeType, safe(reviewFocus), clip(contractText, 1500));
        String ragContext = ragKnowledgeService.contextBlock(ragQuery, "contract", 6);
        List<String> ragReferences = ragKnowledgeService.referenceLines(ragQuery, "contract", 6);
        if (!isEnabled()) {
            log.info("[contract-review] Qwen 未配置 API Key，使用本地 fallback");
            return fallback(safeName, safeType, safeMode, contractText, ragReferences);
        }
        try {
            String raw = callQwen(safeName, safeType, safeMode, reviewFocus, contractText, ragContext);
            log.debug("[contract-review] Qwen 原始返回长度: {}", raw != null ? raw.length() : 0);
            ContractReviewResponse parsed = parse(raw, safeName, safeType, safeMode, contractText, ragReferences);
            if (parsed != null) {
                log.info("[contract-review] 解析 JSON 成功，风险条数 {}", parsed.risks() != null ? parsed.risks().size() : 0);
                return parsed;
            }
            log.warn("[contract-review] 未能从模型输出解析出结构化 JSON，使用 fallback");
        } catch (Exception e) {
            log.warn("[contract-review] 调用或解析 Qwen 失败: {}", e.getMessage(), e);
        }
        return fallback(safeName, safeType, safeMode, contractText, ragReferences);
    }

    private static final int MAX_SOURCE_TEXT = 200_000;

    private static String clipSource(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_SOURCE_TEXT) return text;
        return text.substring(0, MAX_SOURCE_TEXT) + "\n\n…（正文过长，已截断用于页面展示）";
    }

    private boolean isEnabled() {
        return StringUtils.hasText(apiKey);
    }

    private String callQwen(String contractName,
                            String contractType,
                            String reviewMode,
                            String reviewFocus,
                            String contractText,
                            String ragContext) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", contractReviewTemperature);
        body.put("top_p", contractReviewTopP);
        applySeedIfConfigured(body);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt()),
                Map.of("role", "user", "content", userPrompt(contractName, contractType, reviewMode, reviewFocus, contractText, ragContext))
        ));
        log.debug("[contract-review] Qwen 采样 temperature={}, top_p={}, seed配置={}",
                contractReviewTemperature, contractReviewTopP,
                StringUtils.hasText(contractReviewSeed) ? "已设置" : "未设置");
        Map<String, Object> response = restClient.post()
                .uri(baseUrl + "/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(body)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
        Object choicesObj = response == null ? null : response.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) return "";
        Object firstObj = choices.get(0);
        if (!(firstObj instanceof Map<?, ?> first)) return "";
        Object messageObj = first.get("message");
        if (!(messageObj instanceof Map<?, ?> message)) return "";
        Object content = message.get("content");
        return content == null ? "" : content.toString();
    }

    /** OpenAI 兼容接口常见的整数 seed；解析失败则跳过，避免请求被拒 */
    private void applySeedIfConfigured(Map<String, Object> body) {
        if (!StringUtils.hasText(contractReviewSeed)) {
            return;
        }
        try {
            body.put("seed", Integer.parseInt(contractReviewSeed.trim()));
        } catch (NumberFormatException ex) {
            log.warn("[contract-review] CONTRACT_REVIEW_SEED 非整数，已忽略: {}", contractReviewSeed);
        }
    }

    private String systemPrompt() {
        return """
                你是中国法语境下的合同风险审查助手，使用严谨、克制、可落地的审查语言。
                请审查 Word/PDF 提取出的合同全文，识别具体条款位置、风险等级和修改方案。
                风险等级定义：
                - 高风险：可能无效、显失公平、违法违规、重大权利义务缺失、单方免责或重大争议。
                - 中风险：表述模糊、责任边界不清、履行标准不明确、证据和流程缺失。
                - 低风险：格式不规范、术语不统一、编号引用错误、可读性问题。
                对同一份合同全文须保持同一判断尺度：仅在有明确依据时列入 risks，不凑条数、不重复罗列相似问题；不确定是否构成实质风险的勿强行升格等级。
                输出要求：
                1. 先给出简短审查摘要（纯中文叙述，不要使用 Markdown/HTML，不要使用井号、星号、反引号等格式符号）。
                2. 然后输出分隔符 <<<CONTRACT_REVIEW_JSON>>>。
                3. 分隔符后只输出 JSON，不要 Markdown 包裹；JSON 各字符串字段内同样避免将 # * ` 等用作格式标记。
                JSON 字段：
                {
                  "overallRisk": "高风险/中风险/低风险",
                  "summary": "...",
                  "risks": [{"level":"高风险","clauseTitle":"...","location":"第几条/关键词附近","originalClause":"...","issue":"...","legalBasis":"...","suggestion":"...","revisedClause":"...","tags":["..."]}],
                  "revisions": [{"level":"高风险","location":"...","beforeText":"...","afterText":"...","reason":"..."}],
                  "revisedText": "整合后的修订版合同重点条款或全文",
                  "checklist": ["签署前需要核对的事项"]
                }
                """;
    }

    private String userPrompt(String contractName,
                              String contractType,
                              String reviewMode,
                              String reviewFocus,
                              String contractText,
                              String ragContext) {
        return """
                合同名称：%s
                合同类型：%s
                审查模式：%s
                审查重点：%s
                RAG知识库参考片段：%s

                合同全文：
                %s
                """.formatted(
                contractName,
                contractType,
                reviewMode,
                StringUtils.hasText(reviewFocus) ? reviewFocus : "违约责任、付款结算、解除终止、格式条款提示、押金/保证金、争议解决、管辖、知识产权、保密、不可抗力",
                StringUtils.hasText(ragContext) ? ragContext : "未检索到直接相关资料",
                clip(contractText, 30000)
        );
    }

    private ContractReviewResponse parse(String raw, String contractName, String contractType, String reviewMode,
                                         String sourceText, List<String> ragReferences) {
        if (!StringUtils.hasText(raw)) return null;
        String json = raw;
        int idx = raw.indexOf(DELIMITER);
        if (idx >= 0) json = raw.substring(idx + DELIMITER.length()).trim();
        JsonNode root = parseJson(json);
        if (root == null) return null;
        List<ContractRiskItem> risks = readRisks(root.get("risks"));
        List<ContractRevisionItem> revisions = readRevisions(root.get("revisions"));
        return new ContractReviewResponse(
                contractName,
                contractType,
                reviewMode,
                model,
                text(root, "overallRisk", inferOverall(risks)),
                text(root, "summary", "已完成合同风险审查。"),
                stats(risks),
                risks,
                revisions,
                text(root, "revisedText", ""),
                readStringArray(root.get("checklist")),
                ragReferences,
                clipSource(sourceText),
                LocalDateTime.now()
        );
    }

    private ContractReviewResponse fallback(String contractName, String contractType, String reviewMode, String text, List<String> ragReferences) {
        List<ContractRiskItem> risks = new ArrayList<>();
        String normalized = text.replaceAll("\\s+", "");
        if (!containsAny(normalized, "违约责任", "违约金", "赔偿责任")) {
            risks.add(risk("高风险", "违约责任缺失", "违约责任条款附近", "合同未检索到明确违约责任条款。",
                    "未约定违约责任会导致违约后救济路径不清，损失赔偿举证压力较大。",
                    "《民法典》合同编关于违约责任的规则",
                    "补充逾期付款、延迟交付、质量不合格、提前解除等违约情形及责任承担方式。",
                    "任何一方违反本合同约定的，应承担继续履行、采取补救措施、赔偿损失等违约责任；逾期付款的，每逾期一日按应付款项的【】%支付违约金。",
                    List.of("未约定违约责任", "救济不足")));
        }
        if (containsAny(normalized, "押金不退", "保证金不予退还", "无条件没收")) {
            risks.add(risk("高风险", "押金/保证金条款", "押金或保证金关键词附近", findSnippet(text, "押金", "保证金"),
                    "押金或保证金被约定为无条件不退，可能构成过高违约金或免除己方责任。",
                    "违约金调整、公平原则及格式条款规则",
                    "明确扣除条件、损失计算、退还期限和剩余款项返还机制。",
                    "押金仅在乙方存在实际违约并造成损失时，可在损失范围内扣除；扣除后剩余部分应于合同终止后【】日内退还。",
                    List.of("押金条款过重", "退还机制缺失")));
        }
        if (containsAny(normalized, "概不负责", "一切责任由乙方承担", "甲方不承担任何责任")) {
            risks.add(risk("高风险", "单方免责条款", "免责表述附近", findSnippet(text, "概不负责", "不承担任何责任"),
                    "条款可能免除提供方主要责任或加重相对方责任，格式合同中尤其容易被认定无效。",
                    "格式条款提示说明义务及无效规则",
                    "删除绝对免责表述，按过错、原因力和法定责任重新分配责任。",
                    "因一方过错造成对方损失的，过错方应在法律规定及本合同约定范围内承担相应责任。",
                    List.of("格式条款未提示", "单方免责")));
        }
        if (!containsAny(normalized, "争议解决", "管辖", "仲裁", "人民法院")) {
            risks.add(risk("中风险", "争议解决缺失", "合同尾部", "合同未检索到明确争议解决或管辖条款。",
                    "未约定争议解决方式会增加维权成本，并可能导致管辖争议。",
                    "民事诉讼管辖及仲裁协议规则",
                    "补充协商、诉讼或仲裁方式，并明确管辖法院或仲裁机构。",
                    "因本合同引起的争议，双方应先友好协商；协商不成的，任一方可向【合同履行地/被告住所地】有管辖权的人民法院提起诉讼。",
                    List.of("管辖不明确", "维权成本")));
        }
        if (!containsAny(normalized, "通知", "送达", "电子邮箱", "通讯地址")) {
            risks.add(risk("低风险", "通知送达条款缺失", "合同尾部", "合同未检索到通知和送达地址条款。",
                    "缺少送达条款可能影响解除通知、催告函、诉讼材料等文件到达认定。",
                    "意思表示到达规则",
                    "补充双方通讯地址、电子邮箱、手机号及变更通知义务。",
                    "双方确认本合同载明的地址、电子邮箱、手机号为有效送达方式，任一方变更联系方式应提前书面通知对方。",
                    List.of("格式补强", "送达不清")));
        }
        if (risks.isEmpty()) {
            risks.add(risk("低风险", "格式与一致性复核", "全文", "未发现明显高风险关键词。",
                    "仍需人工核对金额、日期、主体名称、签章和附件是否一致。",
                    "合同解释与证据规则",
                    "签署前完成主体资质、授权、附件和付款信息复核。",
                    "建议保留完整签署页、附件清单和履行凭证。",
                    List.of("签署核对", "低风险")));
        }
        List<ContractRevisionItem> revisions = risks.stream()
                .map(r -> new ContractRevisionItem(r.level(), r.location(), r.originalClause(), r.revisedClause(), r.suggestion()))
                .toList();
        return new ContractReviewResponse(
                contractName,
                contractType,
                reviewMode,
                model + "（本地规则兜底）",
                inferOverall(risks),
                "已完成合同扫描，共识别 " + risks.size() + " 处风险。建议优先处理高风险条款，再统一修订中低风险表述。",
                stats(risks),
                risks,
                revisions,
                mergeRevisionsIntoText(text, revisions),
                List.of("核对合同主体名称和授权签署权限", "核对金额、期限、付款账户和发票约定", "确认附件、报价单、验收标准是否作为合同组成部分", "重要格式条款需以加粗、下划线或单独确认方式提示"),
                ragReferences,
                clipSource(text),
                LocalDateTime.now()
        );
    }

    private ContractRiskItem risk(String level, String title, String location, String clause, String issue,
                                  String legalBasis, String suggestion, String revisedClause, List<String> tags) {
        return new ContractRiskItem(level, title, location, clause, issue, legalBasis, suggestion, revisedClause, tags);
    }

    /** 按条款「修改前→修改后」依次替换，得到完整修订正文（与前端对比修订展示一致） */
    private String mergeRevisionsIntoText(String text, List<ContractRevisionItem> revisions) {
        if (!StringUtils.hasText(text) || revisions == null || revisions.isEmpty()) {
            return text;
        }
        String out = text;
        for (ContractRevisionItem item : revisions) {
            String b = item.beforeText() != null ? item.beforeText().trim() : "";
            String a = item.afterText() != null ? item.afterText().trim() : "";
            if (b.length() < 2 || !StringUtils.hasText(a)) {
                continue;
            }
            int idx = out.indexOf(b);
            if (idx >= 0) {
                out = out.substring(0, idx) + a + out.substring(idx + b.length());
            }
        }
        return out;
    }

    private List<ContractRiskItem> readRisks(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<ContractRiskItem> list = new ArrayList<>();
        for (JsonNode item : node) {
            list.add(new ContractRiskItem(
                    text(item, "level", "中风险"),
                    text(item, "clauseTitle", "风险条款"),
                    text(item, "location", "位置待核对"),
                    text(item, "originalClause", ""),
                    text(item, "issue", ""),
                    text(item, "legalBasis", ""),
                    text(item, "suggestion", ""),
                    text(item, "revisedClause", ""),
                    readStringArray(item.get("tags"))
            ));
        }
        return list;
    }

    private List<ContractRevisionItem> readRevisions(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<ContractRevisionItem> list = new ArrayList<>();
        for (JsonNode item : node) {
            list.add(new ContractRevisionItem(
                    text(item, "level", "中风险"),
                    text(item, "location", "位置待核对"),
                    text(item, "beforeText", ""),
                    text(item, "afterText", ""),
                    text(item, "reason", "")
            ));
        }
        return list;
    }

    private List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> list = new ArrayList<>();
        for (JsonNode item : node) {
            String text = item.asText("");
            if (StringUtils.hasText(text)) list.add(text);
        }
        return list;
    }

    private Map<String, Integer> stats(List<ContractRiskItem> risks) {
        Map<String, Integer> stats = new LinkedHashMap<>();
        stats.put("高风险", 0);
        stats.put("中风险", 0);
        stats.put("低风险", 0);
        for (ContractRiskItem risk : risks) {
            stats.computeIfPresent(risk.level(), (k, v) -> v + 1);
        }
        return stats;
    }

    private String inferOverall(List<ContractRiskItem> risks) {
        if (risks.stream().anyMatch(r -> "高风险".equals(r.level()))) return "高风险";
        if (risks.stream().anyMatch(r -> "中风险".equals(r.level()))) return "中风险";
        return "低风险";
    }

    private JsonNode parseJson(String json) {
        if (!StringUtils.hasText(json)) return null;
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start >= 0 && end > start) json = json.substring(start, end + 1);
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String text(JsonNode root, String field, String fallback) {
        if (root == null) return fallback;
        JsonNode node = root.get(field);
        String text = node == null ? "" : node.asText("");
        return StringUtils.hasText(text) ? text : fallback;
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (StringUtils.hasText(word) && text.contains(word)) return true;
        }
        return false;
    }

    private String findSnippet(String text, String... words) {
        for (String word : words) {
            int idx = text.indexOf(word);
            if (idx >= 0) {
                int start = Math.max(0, idx - 80);
                int end = Math.min(text.length(), idx + 180);
                return text.substring(start, end).trim();
            }
        }
        return clip(text, 240);
    }

    private String clip(String text, int max) {
        if (!StringUtils.hasText(text)) return "";
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max) + "\n（内容过长已截断）";
    }

    private String trimSlash(String url) {
        if (url == null) return "";
        String t = url.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }
}
