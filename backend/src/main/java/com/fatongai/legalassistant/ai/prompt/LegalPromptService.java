package com.fatongai.legalassistant.ai.prompt;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LegalPromptService {
    public static final String STRUCTURED_DELIMITER = "[[STRUCTURED_JSON]]";

    public LegalScenario detectScenario(String question) {
        String q = normalize(question);
        if (containsAny(q, "辞退", "裁员", "欠薪", "工资", "工伤", "社保", "仲裁", "劳动合同", "加班")) {
            return LegalScenario.LABOR;
        }
        if (containsAny(q, "离婚", "抚养", "赡养", "婚前", "彩礼", "家暴", "探视")) {
            return LegalScenario.MARRIAGE;
        }
        if (containsAny(q, "退款", "商家", "欺诈", "消费", "三包", "网购", "平台")) {
            return LegalScenario.CONSUMER;
        }
        if (containsAny(q, "合同", "违约", "定金", "押金", "赔偿", "解除合同", "借条", "欠条")) {
            return LegalScenario.CONTRACT;
        }
        return LegalScenario.GENERAL;
    }

    public String buildSystemPrompt(LegalScenario scenario) {
        String sceneTips = switch (scenario) {
            case LABOR -> "优先关注：工资、补偿金、工伤、社保、仲裁时效与证据固定。";
            case MARRIAGE -> "优先关注：子女抚养、财产分割、家暴证据、诉讼/调解路径。";
            case CONSUMER -> "优先关注：交易凭证、平台规则、退赔标准、投诉与诉讼路径。";
            case CONTRACT -> "优先关注：合同效力、违约责任、履约证据、解除/赔偿条件。";
            case GENERAL -> "优先给出普适法律判断路径，并提示补充关键事实。";
        };

        return """
                你是一个面向中国普通用户的 AI 法律助手，目标是“讲人话、给步骤、可执行”。
                回答规则：
                1) 必须使用中文，避免晦涩术语；如出现术语，必须在括号中解释。
                2) 面向用户的可见答复必须为纯文本：用「一、结论」「二、法律依据」「三、维权步骤」「四、证据清单」「五、风险与时效」「六、建议补充信息」等标题单独成行（不用井号、星号、反引号、下划线、波浪线或 HTML）；条目标号可用「1. 」「2. 」或换行分条，层次清楚即可。
                3) 若信息不足，先给“初步结论”，再追加“需补充信息”3-6条问题。
                4) 不得编造具体法条条号；不确定时应明确“建议进一步核对最新法规”。
                5) 若候选法律依据或上传材料中出现【RAG】知识库片段，应优先作为事实核对和法律依据来源；未检索到依据时要说明资料不足，不得把推测写成确定结论。
                6) 必须在回答末尾追加一句：以上仅为法律信息参考，不构成律师法律意见。
                7) 输出格式必须严格遵守：
                   先直接输出可展示给用户的纯文本答复（禁止 Markdown/HTML 及 # * ` _ ~ 等格式符号）；
                   然后单独输出一行：%s
                   最后输出严格 JSON 元数据，不要用代码块包裹，不要输出其他说明。
                   JSON Schema:
                   {
                     "conclusion": "string",
                     "legalBasis": ["string"],
                     "actionSteps": ["string"],
                     "evidenceChecklist": ["string"],
                     "riskWarnings": ["string"],
                     "followupQuestions": ["string"],
                     "finalAnswerPlain": "string（与上文可见答复一致的纯文本，可与 conclusion 等字段内容对应，仍禁止 # * 等符号）"
                   }
                   若需兼容旧字段，可同时输出 finalAnswerMarkdown，但其内容必须与 finalAnswerPlain 相同且同为纯文本。

                当前咨询场景：%s
                场景指导：%s
                """.formatted(STRUCTURED_DELIMITER, scenario.label(), sceneTips);
    }

    public String buildUserPrompt(String question, List<String> attachmentTexts, List<String> recentMessages, List<String> candidateRefs) {
        String history = (recentMessages == null || recentMessages.isEmpty())
                ? "无历史对话"
                : String.join("\n", recentMessages);
        String attachments = (attachmentTexts == null || attachmentTexts.isEmpty())
                ? "无附件"
                : String.join("\n\n---\n\n", attachmentTexts);
        String refs = (candidateRefs == null || candidateRefs.isEmpty())
                ? "无候选法条"
                : String.join("\n", candidateRefs);

        return """
                【用户最新问题】
                %s

                【近期对话上下文（按时间顺序）】
                %s

                【候选法律依据（优先参考）】
                %s

                【用户上传材料摘录】
                %s

                请严格遵守系统提示：可见答复与 JSON 内文字均为纯文本，不得使用井号、星号、反引号等作 Markdown 或强调。
                """.formatted(question, history, refs, attachments);
    }

    private boolean containsAny(String text, String... words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        return text == null ? "" : text.replace(" ", "").trim().toLowerCase();
    }
}
