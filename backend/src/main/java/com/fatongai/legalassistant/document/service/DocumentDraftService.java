package com.fatongai.legalassistant.document.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatongai.legalassistant.document.dto.DocumentGenerateRequest;
import com.fatongai.legalassistant.document.dto.DocumentResult;
import com.fatongai.legalassistant.document.dto.DocumentTemplateView;
import com.fatongai.legalassistant.rag.service.RagKnowledgeService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DocumentDraftService {
    private static final String DELIMITER = "<<<DOCUMENT_JSON>>>";

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final DocumentTemplateService templateService;
    private final RagKnowledgeService ragKnowledgeService;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    public DocumentDraftService(ChatClient.Builder builder,
                                ObjectMapper objectMapper,
                                DocumentTemplateService templateService,
                                RagKnowledgeService ragKnowledgeService) {
        this.chatClient = builder.build();
        this.objectMapper = objectMapper;
        this.templateService = templateService;
        this.ragKnowledgeService = ragKnowledgeService;
    }

    public List<DocumentTemplateView> templates() {
        return templateService.list();
    }

    public DocumentResult generate(DocumentGenerateRequest request) {
        DocumentTemplateView template = templateService.require(request.getDocumentType());
        return produce(template, request, "");
    }

    public DocumentResult optimize(String documentType,
                                   String outputMode,
                                   String facts,
                                   String extraRequirements,
                                   String uploadedText) {
        DocumentTemplateView template = templateService.require(documentType);
        DocumentGenerateRequest request = new DocumentGenerateRequest();
        request.setDocumentType(documentType);
        request.setCategory(template.category());
        request.setScenario(template.scenario());
        request.setOutputMode(StringUtils.hasText(outputMode) ? outputMode : "optimize");
        request.setFacts(facts);
        request.setExtraRequirements(extraRequirements);
        request.setFields(Map.of());
        return produce(template, request, uploadedText);
    }

    private DocumentResult produce(DocumentTemplateView template, DocumentGenerateRequest request, String uploadedText) {
        List<String> missing = missingFields(template.fields(), request.getFields());
        String ragQuery = ragQuery(template, request, uploadedText);
        String ragContext = ragKnowledgeService.contextBlock(ragQuery, "document", 5);
        List<String> ragReferences = ragKnowledgeService.referenceLines(ragQuery, "document", 5);
        if (!hasAi()) {
            return fallback(template, request, uploadedText, missing, ragReferences);
        }
        try {
            String raw = chatClient.prompt()
                    .system(systemPrompt())
                    .user(userPrompt(template, request, uploadedText, missing, ragContext))
                    .call()
                    .content();
            DocumentResult parsed = parse(raw, template, request, missing, ragReferences);
            if (parsed != null) return parsed;
        } catch (Exception ignored) {
        }
        return fallback(template, request, uploadedText, missing, ragReferences);
    }

    private boolean hasAi() {
        return StringUtils.hasText(apiKey);
    }

    private String systemPrompt() {
        return """
                你是中国法语境下的法律文书起草助手，根据用户材料生成或优化「可直接落款或提交前微调的正式书面文书」。
                一、文书正文（content 字段，以及分隔符 <<<DOCUMENT_JSON>>> 之前若单独输出的部分）必须为纯文本，禁止 Markdown、禁止 HTML，禁止任何以格式为目的的特殊符号，包括但不限于：井号 #、星号 *、反引号 `、下划线 _、波浪线 ~、尖括号 <>、方括号 [] 作标记、反斜杠转义格式等。
                1. 层次与版式仅用普通汉字、数字、标点与换行表现：首行写文书名称（与 JSON 的 title 一致），独占一行；空一行后写正文。正文用大节标题如「一、当事人」「二、约定事项」「三、违约责任与争议解决」「四、签署」等单独成行；节下分款可用「（一）」「（二）」或「1.」「2.」起行，款内可分段，段与段之间空一行。全篇书面语，条款清楚，不得出现「结构化字段」「核心条款提示」「特别提示」「免责声明」「系统说明」等非文书板块。
                2. 将用户已填字段与事实描述写入相应条款叙述，不得再列字段清单式说明。
                3. generate：完整书面文书；optimize：优化措辞并补缺，输出仍为完整文书正文，不得夹带修改过程说明；risk：正文仍为规范文书，风险只写入 JSON 的 riskAnnotations。
                二、与正文分离的 JSON（分隔符之后）：optimizationSuggestions、riskAnnotations、evidenceChecklist 每条为纯中文短句或短段，同样禁止 # * ` 等格式符号。参考性说明只放在上述数组中，不得写入 content。
                三、输出顺序：先仅输出上述纯文本正文，再单独一行输出分隔符 <<<DOCUMENT_JSON>>>，再输出 JSON。JSON 键：title, content, optimizationSuggestions, riskAnnotations, evidenceChecklist, status。其中 content 与前面可见正文须一致或等价，均为纯文本。
                """;
    }

    private String userPrompt(DocumentTemplateView template, DocumentGenerateRequest request, String uploadedText, List<String> missing, String ragContext) {
        return """
                文书类型：%s
                文书类别：%s
                适用场景：%s
                输出模式：%s
                必填字段：%s
                已填字段：%s
                缺失字段：%s
                事实描述：%s
                额外要求：%s
                已上传文书内容：%s
                关键条款提示（仅供你构思 optimizationSuggestions，禁止写入 content 正文）：%s
                RAG知识库参考片段（用于核对条款、证据与风险，不得编造未出现的资料）：%s

                再次强调：content 与分隔符前正文须为纯文本，可用「一、」「二、」「（一）」「1.」等汉字与标点分层，禁止使用 # * ` _ ~ 及 HTML。不得出现结构化字段清单、条款提示、免责声明等与正式文书无关的板块。optimizationSuggestions、riskAnnotations、evidenceChecklist 与正文严格分离。
                """.formatted(
                template.name(),
                template.category(),
                nvl(request.getScenario(), template.scenario()),
                mode(request.getOutputMode()),
                template.fields(),
                fieldsText(request.getFields()),
                missing,
                nvl(request.getFacts(), "未填写"),
                nvl(request.getExtraRequirements(), "无"),
                clip(uploadedText, 6000),
                template.clauseTips(),
                StringUtils.hasText(ragContext) ? ragContext : "未检索到直接相关资料"
        );
    }

    private String ragQuery(DocumentTemplateView template, DocumentGenerateRequest request, String uploadedText) {
        return String.join("\n",
                template.name(),
                template.category(),
                nvl(request.getScenario(), template.scenario()),
                nvl(request.getFacts(), ""),
                nvl(request.getExtraRequirements(), ""),
                clip(uploadedText, 1200));
    }

    private DocumentResult parse(String raw, DocumentTemplateView template, DocumentGenerateRequest request, List<String> missing, List<String> ragReferences) {
        if (!StringUtils.hasText(raw)) return null;
        String visible = raw;
        String json = "";
        int idx = raw.indexOf(DELIMITER);
        if (idx >= 0) {
            visible = raw.substring(0, idx).trim();
            json = raw.substring(idx + DELIMITER.length()).trim();
        }
        JsonNode root = parseJson(json);
        if (root == null) {
            return newResult(template, request, visible, missing, template.clauseTips(), List.of(), List.of(), List.of(), ragReferences, "已生成");
        }
        String content = text(root, "content");
        if (!StringUtils.hasText(content)) content = visible;
        return new DocumentResult(
                template.type(),
                text(root, "title", template.name()),
                mode(request.getOutputMode()),
                text(root, "status", "已生成"),
                content,
                template.fields(),
                missing,
                template.clauseTips(),
                array(root, "optimizationSuggestions"),
                array(root, "riskAnnotations"),
                array(root, "evidenceChecklist"),
                ragReferences,
                LocalDateTime.now()
        );
    }

    private DocumentResult fallback(DocumentTemplateView template, DocumentGenerateRequest request, String uploadedText, List<String> missing, List<String> ragReferences) {
        String content = StringUtils.hasText(uploadedText)
                ? optimizedFallbackContent(template, uploadedText)
                : generatedFallbackContent(template, request);
        List<String> suggestions = new ArrayList<>(template.clauseTips());
        if (!missing.isEmpty()) {
            suggestions.add("建议补充缺失信息：" + String.join("、", missing));
        }
        if (StringUtils.hasText(uploadedText)) {
            suggestions.add("已根据上传文书进行结构化审阅，可重点补充主体身份、金额、期限、违约责任和争议解决条款。");
        }
        List<String> risks = new ArrayList<>();
        risks.add("文书生成后仍需核对身份信息、金额、日期、签章和附件证据。");
        if (!missing.isEmpty()) risks.add("存在关键字段缺失，直接使用可能导致条款不明确或请求难以支持。");
        if ("risk".equals(mode(request.getOutputMode()))) risks.add("风险标注模式下应逐项核对高风险条款并保留修改记录。");
        return newResult(template, request, content, missing, template.clauseTips(), suggestions, risks,
                evidence(template), ragReferences, hasAi() ? "已生成" : "本地模板草稿");
    }

    private String generatedFallbackContent(DocumentTemplateView template, DocumentGenerateRequest request) {
        Map<String, String> fields = request.getFields() == null ? Map.of() : request.getFields();
        StringBuilder sb = new StringBuilder();
        sb.append(template.name()).append("\n\n");
        sb.append("一、当事人与基础信息\n\n");
        sb.append("立据人确认下列记载为真实意思表示，除留空待填外可于签署前据实补全。\n\n");
        for (String field : template.fields()) {
            sb.append("1. ").append(field).append("：").append(nvl(fields.get(field), "【待填写】")).append("\n");
        }
        sb.append("\n");
        sb.append("二、主要约定事项\n\n");
        String facts = nvl(request.getFacts(), "");
        if (StringUtils.hasText(facts)) {
            sb.append(facts.trim()).append("\n\n");
        } else {
            sb.append("事实经过、金额履行、证据材料等由当事人在签署前据实补充。\n\n");
        }
        sb.append("（一）效力与份数\n\n");
        sb.append("本文书一式两份，各方各执一份，自各方签署之日起生效。\n\n");
        sb.append("三、签署\n\n");
        sb.append("立据人（签字或盖章）：________________\n\n");
        sb.append("签署日期：____年____月____日");
        return sb.toString();
    }

    private String optimizedFallbackContent(DocumentTemplateView template, String uploadedText) {
        String body = clip(uploadedText, 5000).trim();
        if (!StringUtils.hasText(body)) {
            return template.name() + "\n\n（正文待补充）";
        }
        return template.name() + "\n\n" + body;
    }

    private DocumentResult newResult(DocumentTemplateView template,
                                     DocumentGenerateRequest request,
                                     String content,
                                     List<String> missing,
                                     List<String> clauseTips,
                                     List<String> suggestions,
                                     List<String> risks,
                                     List<String> evidenceChecklist,
                                     List<String> ragReferences,
                                     String status) {
        return new DocumentResult(
                template.type(),
                template.name(),
                mode(request.getOutputMode()),
                status,
                content,
                template.fields(),
                missing,
                clauseTips,
                suggestions,
                risks,
                evidenceChecklist,
                ragReferences,
                LocalDateTime.now()
        );
    }

    private List<String> missingFields(List<String> required, Map<String, String> fields) {
        Map<String, String> safe = fields == null ? Map.of() : fields;
        return required.stream()
                .filter(field -> !StringUtils.hasText(safe.get(field)))
                .toList();
    }

    private String fieldsText(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) return "无";
        Map<String, String> safe = new LinkedHashMap<>(fields);
        return safe.toString();
    }

    private List<String> evidence(DocumentTemplateView template) {
        if (template.scenario().contains("借贷") || template.name().contains("借条") || template.name().contains("欠条")) {
            return List.of("转账凭证", "聊天记录或催款记录", "双方身份证明", "还款记录");
        }
        if (template.name().contains("起诉状") || template.name().contains("答辩状")) {
            return List.of("合同或协议", "付款/履行凭证", "沟通记录", "主体身份材料", "证据目录");
        }
        if (template.name().contains("劳动")) {
            return List.of("劳动合同", "工资流水", "考勤记录", "社保记录", "解除或通知文件");
        }
        return List.of("主体身份证明", "原始合同或文书", "付款凭证", "沟通记录");
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

    private List<String> array(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            String text = item.asText("");
            if (StringUtils.hasText(text)) out.add(text);
        }
        return out;
    }

    private String text(JsonNode root, String field) {
        return text(root, field, "");
    }

    private String text(JsonNode root, String field, String fallback) {
        JsonNode node = root.get(field);
        String text = node == null ? "" : node.asText("");
        return StringUtils.hasText(text) ? text : fallback;
    }

    private String mode(String mode) {
        if ("optimize".equalsIgnoreCase(mode)) return "optimize";
        if ("risk".equalsIgnoreCase(mode)) return "risk";
        return "generate";
    }

    private String nvl(String text, String fallback) {
        return StringUtils.hasText(text) ? text : fallback;
    }

    private String clip(String text, int max) {
        if (!StringUtils.hasText(text)) return "";
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max) + "\n（内容过长已截断）";
    }
}
