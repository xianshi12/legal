package com.fatongai.legalassistant.guide.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatongai.legalassistant.guide.dto.*;
import com.fatongai.legalassistant.rag.service.RagKnowledgeService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class GuideService {
    private static final String CUSTOM_TYPE = "custom";
    private static final String DELIMITER = "<<<GUIDE_JSON>>>";

    private final GuideTemplateService templateService;
    private final LimitationService limitationService;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final RagKnowledgeService ragKnowledgeService;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    public GuideService(GuideTemplateService templateService,
                        LimitationService limitationService,
                        ChatClient.Builder builder,
                        ObjectMapper objectMapper,
                        RagKnowledgeService ragKnowledgeService) {
        this.templateService = templateService;
        this.limitationService = limitationService;
        this.chatClient = builder.build();
        this.objectMapper = objectMapper;
        this.ragKnowledgeService = ragKnowledgeService;
    }

    public List<GuideTemplateView> templates() {
        return templateService.list();
    }

    public GuideResponse guide(GuideRequest request) {
        String flowType = StringUtils.hasText(request.getFlowType()) ? request.getFlowType() : "labor-arbitration";
        String limitationType = StringUtils.hasText(request.getLimitationType())
                ? request.getLimitationType()
                : templateService.get(flowType).limitationType();
        LimitationResult limitation = limitationService.calculate(limitationType, request.getIncidentDate());
        if (CUSTOM_TYPE.equals(flowType) || StringUtils.hasText(request.getCustomFlow())) {
            return customGuide(request, limitation);
        }
        GuideResponse base = templateService.build(flowType, limitation);
        String ragQuery = String.join("\n", base.title(), base.category(), nvl(request.getFacts(), ""));
        List<String> ragReferences = ragKnowledgeService.referenceLines(ragQuery, "guide", 4);
        List<String> attention = new ArrayList<>(base.attentionPoints());
        attention.addAll(ragReferences);
        return new GuideResponse(base.flowType(), base.title(), base.category(), base.summary(), base.steps(),
                base.materials(), attention, base.evidenceChecklist(), limitation, false, ragReferences, LocalDateTime.now());
    }

    public LimitationResult limitation(String limitationType, String incidentDate) {
        return limitationService.calculate(limitationType, incidentDate);
    }

    private GuideResponse customGuide(GuideRequest request, LimitationResult limitation) {
        String ragQuery = String.join("\n", nvl(request.getCustomFlow(), ""), nvl(request.getFacts(), ""));
        String ragContext = ragKnowledgeService.contextBlock(ragQuery, "guide", 5);
        List<String> ragReferences = ragKnowledgeService.referenceLines(ragQuery, "guide", 5);
        if (StringUtils.hasText(apiKey)) {
            try {
                String raw = chatClient.prompt()
                        .system(systemPrompt())
                        .user(userPrompt(request, limitation, ragContext))
                        .call()
                        .content();
                GuideResponse parsed = parseAi(raw, request, limitation, ragReferences);
                if (parsed != null) return parsed;
            } catch (Exception ignored) {
            }
        }
        return localCustom(request, limitation, ragReferences);
    }

    private String systemPrompt() {
        return """
                你是中国法语境下的维权流程指引助手。请根据用户自定义流程和案情，输出可执行流程。
                必须包含：分步说明、时间节点、需准备材料、注意事项、证据清单、时效提示。
                分隔符前的摘要与说明须为纯文本，禁止使用 Markdown 或 HTML，禁止使用井号、星号、反引号等作格式标记。
                先输出简短摘要，再输出分隔符 <<<GUIDE_JSON>>>，分隔符后只输出 JSON。
                JSON 字段：
                {
                  "title":"...",
                  "category":"...",
                  "summary":"...",
                  "steps":[{"name":"...","time":"...","desc":"...","active":true,"warning":false}],
                  "materials":["..."],
                  "attentionPoints":["..."],
                  "evidenceChecklist":["..."]
                }
                """;
    }

    private String userPrompt(GuideRequest request, LimitationResult limitation, String ragContext) {
        return """
                用户选择流程：%s
                用户自定义流程：%s
                案情描述：%s
                RAG知识库参考片段：%s
                时效类型：%s
                起算日期：%s
                截止日期：%s
                时效状态：%s
                """.formatted(
                nvl(request.getFlowType(), "custom"),
                nvl(request.getCustomFlow(), "未填写"),
                nvl(request.getFacts(), "未填写"),
                StringUtils.hasText(ragContext) ? ragContext : "未检索到直接相关资料",
                limitation.limitationType(),
                limitation.startDate(),
                limitation.deadline(),
                limitation.status()
        );
    }

    private GuideResponse parseAi(String raw, GuideRequest request, LimitationResult limitation, List<String> ragReferences) {
        if (!StringUtils.hasText(raw)) return null;
        String json = raw;
        int idx = raw.indexOf(DELIMITER);
        if (idx >= 0) json = raw.substring(idx + DELIMITER.length());
        JsonNode root = parseJson(json);
        if (root == null) return null;
        List<GuideStep> steps = new ArrayList<>();
        JsonNode stepNode = root.get("steps");
        if (stepNode != null && stepNode.isArray()) {
            for (JsonNode item : stepNode) {
                steps.add(new GuideStep(
                        text(item, "name", "下一步"),
                        text(item, "time", "尽快"),
                        text(item, "desc", ""),
                        item.path("active").asBoolean(false),
                        item.path("warning").asBoolean(false)
                ));
            }
        }
        if (steps.isEmpty()) return null;
        return new GuideResponse(
                "custom",
                text(root, "title", nvl(request.getCustomFlow(), "自定义流程指引")),
                text(root, "category", "自定义流程"),
                text(root, "summary", "已根据案情生成流程指引。"),
                steps,
                array(root.get("materials")),
                array(root.get("attentionPoints")),
                array(root.get("evidenceChecklist")),
                limitation,
                true,
                ragReferences,
                LocalDateTime.now()
        );
    }

    private GuideResponse localCustom(GuideRequest request, LimitationResult limitation, List<String> ragReferences) {
        String title = StringUtils.hasText(request.getCustomFlow()) ? request.getCustomFlow() : "自定义维权流程";
        List<GuideStep> steps = List.of(
                new GuideStep("明确目标和管辖", "立即", "确认希望达成的结果、对方主体信息、主管机关或法院/仲裁机构。", true, false),
                new GuideStep("固定证据", "1-3日内", "按时间线整理合同、聊天记录、付款凭证、通知函、照片视频等材料。", false, false),
                new GuideStep("发送书面通知", "准备后", "通过可留痕方式提出明确请求和履行期限，保留送达记录。", false, false),
                new GuideStep("申请调解/投诉/立案", "协商无果", "根据纠纷类型选择仲裁、行政投诉、人民调解或法院起诉。", false, true),
                new GuideStep("跟进执行", "取得结果后", "对方不履行调解书、裁决书或判决书的，及时申请强制执行。", false, false)
        );
        return new GuideResponse("custom", title, "自定义流程",
                "已根据通用维权路径生成流程指引；如纠纷类型特殊，建议进一步核对主管机关和前置程序。",
                steps,
                List.of("主体身份材料", "合同或协议", "付款凭证", "沟通记录", "书面通知和送达凭证", "损失计算表"),
                List.of(limitation.note(), "所有关键沟通尽量使用可留痕方式", "请求金额和证据需逐项对应"),
                List.of("合同/订单", "转账凭证", "聊天记录", "催告记录", "照片视频", "对方身份信息"),
                limitation,
                false,
                ragReferences,
                LocalDateTime.now());
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

    private List<String> array(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> list = new ArrayList<>();
        for (JsonNode item : node) {
            String text = item.asText("");
            if (StringUtils.hasText(text)) list.add(text);
        }
        return list;
    }

    private String text(JsonNode root, String field, String fallback) {
        String value = root.path(field).asText("");
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String nvl(String text, String fallback) {
        return StringUtils.hasText(text) ? text : fallback;
    }
}
