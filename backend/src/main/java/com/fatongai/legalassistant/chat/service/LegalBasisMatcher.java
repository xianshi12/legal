package com.fatongai.legalassistant.chat.service;

import com.fatongai.legalassistant.law.dto.LawSearchResult;
import com.fatongai.legalassistant.law.service.LawArticleService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class LegalBasisMatcher {
    private final LawArticleService lawArticleService;

    public LegalBasisMatcher(LawArticleService lawArticleService) {
        this.lawArticleService = lawArticleService;
    }

    public List<String> matchReferences(String question) {
        String q = normalize(question);
        Set<String> refs = new LinkedHashSet<>();
        try {
            List<LawSearchResult> results = lawArticleService.search(question, "fullText", List.of(), null, null, "relevance", 1, 4).items();
            for (LawSearchResult law : results) {
                refs.add("《" + law.lawName() + "》" + law.articleNo() + "（" + law.scoreLabel() + "）");
            }
        } catch (Exception ignored) {
        }

        // 劳动纠纷
        if (containsAny(q, "辞退", "解除劳动合同", "补偿金", "n+1")) {
            refs.add("《劳动合同法》第39条（劳动者过错解除）");
            refs.add("《劳动合同法》第40条（无过错解除）");
            refs.add("《劳动合同法》第46条（经济补偿情形）");
            refs.add("《劳动合同法》第47条（经济补偿计算）");
        }
        if (containsAny(q, "欠薪", "拖欠工资", "工资")) {
            refs.add("《劳动合同法》第30条（按时足额支付劳动报酬）");
            refs.add("《劳动法》第50条（工资支付）");
        }
        if (containsAny(q, "工伤", "工伤认定", "工伤赔偿")) {
            refs.add("《工伤保险条例》第17条（工伤认定申请时限）");
            refs.add("《工伤保险条例》第35-37条（工伤待遇）");
        }

        // 婚姻家事
        if (containsAny(q, "离婚", "抚养权", "探视权", "子女抚养")) {
            refs.add("《民法典》婚姻家庭编（离婚与子女抚养相关条款）");
            refs.add("《民法典》第1084条（离婚后子女抚养）");
        }

        // 消费维权
        if (containsAny(q, "退款", "欺诈", "商家", "网购", "平台")) {
            refs.add("《消费者权益保护法》第55条（欺诈三倍赔偿）");
            refs.add("《消费者权益保护法》第24条（七日无理由退货）");
        }

        // 合同/借贷
        if (containsAny(q, "借条", "欠条", "民间借贷", "利率")) {
            refs.add("《民法典》合同编（借款合同相关条款）");
            refs.add("《最高人民法院关于审理民间借贷案件适用法律若干问题的规定》");
        }
        if (containsAny(q, "押金", "租房", "房东", "提前解约")) {
            refs.add("《民法典》合同编（租赁合同相关条款）");
            refs.add("《民法典》第577条（违约责任）");
        }

        if (refs.isEmpty()) {
            refs.add("建议结合《民法典》及相关司法解释进行进一步核对");
        }
        return new ArrayList<>(refs);
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
