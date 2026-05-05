package com.fatongai.legalassistant.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatongai.legalassistant.ai.dto.StructuredConsultOutput;
import com.fatongai.legalassistant.ai.prompt.LegalPromptService;
import com.fatongai.legalassistant.ai.prompt.LegalScenario;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Service
public class SpringAiDeepSeekService implements LegalAiService {

    private final ChatClient chatClient;
    private final LegalPromptService legalPromptService;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    public SpringAiDeepSeekService(ChatClient.Builder builder,
                                   LegalPromptService legalPromptService,
                                   ObjectMapper objectMapper) {
        this.chatClient = builder.build();
        this.legalPromptService = legalPromptService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiAnswer answer(String question, List<String> attachmentTexts, List<String> recentMessages, List<String> candidateRefs) {
        return streamAnswer(question, attachmentTexts, recentMessages, candidateRefs, null);
    }

    @Override
    public AiAnswer streamAnswer(String question,
                                 List<String> attachmentTexts,
                                 List<String> recentMessages,
                                 List<String> candidateRefs,
                                 Consumer<String> tokenConsumer) {
        LegalScenario scenario = legalPromptService.detectScenario(question);
        String system = legalPromptService.buildSystemPrompt(scenario);
        String user = legalPromptService.buildUserPrompt(question, attachmentTexts, recentMessages, candidateRefs);

        if (apiKey == null || apiKey.isBlank()) {
            AiAnswer fallback = localFallback(scenario);
            emitText(fallback.content(), tokenConsumer);
            return fallback;
        }

        StringBuilder rawContent = new StringBuilder();
        VisibleStreamEmitter visibleEmitter = new VisibleStreamEmitter(tokenConsumer);
        try {
            chatClient.prompt()
                    .system(system)
                    .user(user)
                    .stream()
                    .content()
                    .doOnNext(chunk -> {
                        rawContent.append(chunk);
                        visibleEmitter.accept(chunk);
                    })
                    .blockLast();
            visibleEmitter.complete();
        } catch (Exception ex) {
            if (rawContent.isEmpty()) {
                AiAnswer fallback = busyFallback(scenario);
                emitText(fallback.content(), tokenConsumer);
                return fallback;
            }
        }

        return toAiAnswer(rawContent.toString(), scenario);
    }

    private AiAnswer toAiAnswer(String rawContent, LegalScenario scenario) {
        StructuredConsultOutput parsed = tryParseStructured(rawContent);
        if (parsed == null) {
            return new AiAnswer(visiblePlainAnswer(rawContent), scenario.label(), "", List.of(), List.of(), List.of(), List.of(), List.of());
        }

        String plain = visiblePlainAnswer(rawContent);
        if (plain.isBlank()) {
            plain = buildPlainTextFromStructured(parsed);
        }
        return new AiAnswer(
                plain,
                scenario.label(),
                nvl(parsed.getConclusion()),
                safe(parsed.getLegalBasis()),
                safe(parsed.getActionSteps()),
                safe(parsed.getEvidenceChecklist()),
                safe(parsed.getRiskWarnings()),
                safe(parsed.getFollowupQuestions())
        );
    }

    private AiAnswer localFallback(LegalScenario scenario) {
        return new AiAnswer("""
                （当前未配置 DeepSeek API Key，已返回本地演示回答）

                一、初步建议
                请补充关键事实，例如时间、金额、证据、双方身份和你希望达到的结果，我可以据此给出更准确的维权路径。

                二、你可以先提供的信息
                1. 事件发生时间线
                2. 合同、聊天记录、转账凭证、工资条等证据是否完整
                3. 你希望达成的目标，例如退款、赔偿、解除合同或恢复劳动关系

                以上仅为法律信息参考，不构成律师法律意见。
                """, scenario.label(), "请先补充关键事实信息", List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private AiAnswer busyFallback(LegalScenario scenario) {
        return new AiAnswer("""
                当前智能法律咨询服务暂时繁忙，请稍后重试。

                你也可以先补充以下信息，我将更容易给出精准建议：
                1. 事件发生时间线
                2. 涉及金额或损失
                3. 已有证据，例如合同、聊天记录、转账凭证
                4. 你希望达成的目标，例如退款、赔偿、解除或继续履行

                以上仅为法律信息参考，不构成律师法律意见。
                """, scenario.label(), "服务暂时繁忙，请稍后重试", List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private StructuredConsultOutput tryParseStructured(String content) {
        if (content == null || content.isBlank()) return null;
        String json = extractJson(content);
        if (json.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(json);
            StructuredConsultOutput out = new StructuredConsultOutput();
            out.setConclusion(readText(root, "conclusion"));
            out.setLegalBasis(readArray(root, "legalBasis"));
            out.setActionSteps(readArray(root, "actionSteps"));
            out.setEvidenceChecklist(readArray(root, "evidenceChecklist"));
            out.setRiskWarnings(readArray(root, "riskWarnings"));
            out.setFollowupQuestions(readArray(root, "followupQuestions"));
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractJson(String content) {
        int delimiter = content.indexOf(LegalPromptService.STRUCTURED_DELIMITER);
        String jsonPart = delimiter >= 0
                ? content.substring(delimiter + LegalPromptService.STRUCTURED_DELIMITER.length())
                : content;
        int start = jsonPart.indexOf('{');
        int end = jsonPart.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return jsonPart.substring(start, end + 1);
        }
        return "";
    }

    private String visiblePlainAnswer(String content) {
        if (content == null) return "";
        int delimiter = content.indexOf(LegalPromptService.STRUCTURED_DELIMITER);
        if (delimiter >= 0) {
            return content.substring(0, delimiter).trim();
        }
        String json = extractJson(content);
        if (!json.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(json);
                String plain = readFinalAnswerPlain(root);
                if (!plain.isBlank()) {
                    return plain.trim();
                }
            } catch (Exception ignored) {
            }
        }
        return content.trim();
    }

    /** 优先 finalAnswerPlain，兼容旧字段 finalAnswerMarkdown */
    private String readFinalAnswerPlain(JsonNode root) {
        String a = readText(root, "finalAnswerPlain");
        if (!a.isBlank()) {
            return a;
        }
        return readText(root, "finalAnswerMarkdown");
    }

    private List<String> readArray(JsonNode root, String field) {
        List<String> out = new ArrayList<>();
        JsonNode n = root.get(field);
        if (n == null || !n.isArray()) return out;
        for (JsonNode item : n) {
            String text = item.asText("");
            if (!text.isBlank()) out.add(text);
        }
        return out;
    }

    private String readText(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null) return "";
        return n.asText("");
    }

    private List<String> safe(List<String> list) {
        return list == null ? List.of() : list;
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    private String buildPlainTextFromStructured(StructuredConsultOutput out) {
        StringBuilder sb = new StringBuilder();
        if (!nvl(out.getConclusion()).isBlank()) {
            sb.append("一、结论\n\n").append(out.getConclusion()).append("\n\n");
        }
        appendNumberedSection(sb, "二、法律依据", out.getLegalBasis());
        appendNumberedSection(sb, "三、维权步骤", out.getActionSteps());
        appendNumberedSection(sb, "四、证据清单", out.getEvidenceChecklist());
        appendNumberedSection(sb, "五、风险与时效", out.getRiskWarnings());
        appendNumberedSection(sb, "六、建议补充信息", out.getFollowupQuestions());
        sb.append("以上仅为法律信息参考，不构成律师法律意见。");
        return sb.toString();
    }

    private void appendNumberedSection(StringBuilder sb, String title, List<String> items) {
        if (items == null || items.isEmpty()) return;
        sb.append(title).append("\n\n");
        int n = 1;
        for (String item : items) {
            sb.append(n++).append(". ").append(item).append("\n");
        }
        sb.append("\n");
    }

    private void emitText(String text, Consumer<String> tokenConsumer) {
        if (tokenConsumer == null || text == null || text.isBlank()) return;
        int step = 18;
        for (int i = 0; i < text.length(); i += step) {
            tokenConsumer.accept(text.substring(i, Math.min(text.length(), i + step)));
        }
    }

    /** 流式输出时只推送分隔符之前的可见纯文本，避免把 JSON 推给前端 */
    private static class VisibleStreamEmitter {
        private final Consumer<String> tokenConsumer;
        private final StringBuilder buffer = new StringBuilder();
        private int emittedIndex = 0;
        private boolean delimiterSeen = false;

        private VisibleStreamEmitter(Consumer<String> tokenConsumer) {
            this.tokenConsumer = tokenConsumer;
        }

        private void accept(String chunk) {
            if (tokenConsumer == null || delimiterSeen || chunk == null || chunk.isEmpty()) return;
            buffer.append(chunk);
            int delimiterIndex = buffer.indexOf(LegalPromptService.STRUCTURED_DELIMITER);
            if (delimiterIndex >= 0) {
                emitRange(emittedIndex, delimiterIndex);
                emittedIndex = delimiterIndex;
                delimiterSeen = true;
                return;
            }

            int safeEnd = Math.max(emittedIndex, buffer.length() - LegalPromptService.STRUCTURED_DELIMITER.length() + 1);
            emitRange(emittedIndex, safeEnd);
            emittedIndex = safeEnd;
        }

        private void complete() {
            if (tokenConsumer == null || delimiterSeen) return;
            emitRange(emittedIndex, buffer.length());
            emittedIndex = buffer.length();
        }

        private void emitRange(int start, int end) {
            if (end <= start) return;
            tokenConsumer.accept(buffer.substring(start, end));
        }
    }
}
