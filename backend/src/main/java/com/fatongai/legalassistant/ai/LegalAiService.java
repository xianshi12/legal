package com.fatongai.legalassistant.ai;

import java.util.List;
import java.util.function.Consumer;

public interface LegalAiService {
    AiAnswer answer(String question, List<String> attachmentTexts, List<String> recentMessages, List<String> candidateRefs);

    AiAnswer streamAnswer(String question,
                          List<String> attachmentTexts,
                          List<String> recentMessages,
                          List<String> candidateRefs,
                          Consumer<String> tokenConsumer);

    record AiAnswer(
            String content,
            String scenario,
            String conclusion,
            List<String> legalBasis,
            List<String> actionSteps,
            List<String> evidenceChecklist,
            List<String> riskWarnings,
            List<String> followupQuestions
    ) {
    }
}
