package com.fatongai.legalassistant.law.service;

import com.fatongai.legalassistant.law.support.LawInterpretationTexts;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 法条入库时的通俗解读、适用场景与例外提示（规则生成，辅助非专业人士阅读）。
 * 页面抓取与 NPC 同步共用。
 */
public final class LawArticleDigestHelper {

    private LawArticleDigestHelper() {
    }

    private static final Pattern TIAO_HEADING = Pattern.compile(
            "第\\s*[一二三四五六七八九十百千万零〇两0-9]+\\s*条(?:之一|之二|之三)?");

    /**
     * 正文是否主要为「第一条 第二条 …」式的条号串联（条间距很短、实质语句极少），NPC JSON 常见。
     * 用于拒绝把目录当「全文」解读，并触发从详情页等渠道补抓。
     */
    public static boolean looksLikeSparseArticleHeadingList(String raw) {
        if (!StringUtils.hasText(raw) || raw.length() < 120) {
            return false;
        }
        String text = stripTechnicalNoise(raw.replace('\u00A0', ' '))
                .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        Matcher m = TIAO_HEADING.matcher(text);
        List<Integer> starts = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
        }
        if (starts.size() < 5) {
            return false;
        }
        double sumGap = 0;
        for (int i = 1; i < starts.size(); i++) {
            sumGap += starts.get(i) - starts.get(i - 1);
        }
        double avgGap = sumGap / (starts.size() - 1);
        if (avgGap > 48) {
            return false;
        }
        String compact = text.replaceAll("\\s+", "");
        String stripped = TIAO_HEADING.matcher(compact).replaceAll("");
        String substantive = stripped.replaceAll("[、，,;；第章节约附则0-9\\s]+", "");
        return substantive.length() * 10 < compact.length();
    }

    /**
     * 去掉正文/目录里混入的下载路径、附件扩展名、URL 等噪声，避免进入通俗解读或向量文本。
     */
    public static String stripTechnicalNoise(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String s = content.replace('\u00A0', ' ');
        s = s.replaceAll("https?://\\S+", " ");
        s = s.replaceAll("(?i)\\b[a-zA-Z]:\\\\\\S+", " ");
        s = s.replaceAll("(?i)\\S+\\.(?:docx?|pdf|ofd|html|htm)\\b(?:\\?\\S*)?", " ");
        s = s.replaceAll("(?i)/prod/\\d{8}/\\S+", " ");
        s = s.replaceAll("(?i)\\bflk\\.npc\\.gov\\.cn\\S*", " ");
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return s;
    }

    public static String colloquialInterpretation(String lawName, String articleNo, String content) {
        String law = lawName == null ? "" : lawName.trim();
        String effective = effectiveLegalContent(content);
        String text = law + effective;
        String art = articleNo == null ? "" : articleNo.trim();

        if ("全文".equals(art) || art.contains("全文")) {
            if (looksLikeSparseArticleHeadingList(content == null ? "" : content)) {
                return "【大白话】当前入库的正文主要是「条号目录」而不是逐条正文，无法按条做有效解读。"
                        + "请删除本法规相关记录后重新同步（系统会从国家库 JSON 与详情页中择优补抓正文）；正式引用请以国家法律法规数据库公布的文本为准。";
            }
            String g = firstGistSentence(effective, 180);
            if (!StringUtils.hasText(g)) {
                return "【大白话】这份法规已收录来源信息，但暂时没有识别出足够清晰的权利义务条款。建议先打开来源页面，用关键词定位与您事项相关的具体条款。";
            }
            return "【大白话】这份法规的重点不是开头的制定目的或目录，而是后面直接规定权利、义务、禁止事项和法律后果的条款。"
                    + "当前能抓到的核心内容可以先理解为：" + g
                    + "。办具体事情时，建议继续用关键词定位到对应条号，再看有没有例外、程序要求或最新修订。";
        }

        if (looksLikeSparseArticleHeadingList(content == null ? "" : content)) {
            return "【大白话】本条入库内容疑似仅为条号或目录片段，缺少可读的条文正文。"
                    + "建议打开来源页核对正式文本，或删除后重新同步法规。";
        }

        if (text.contains("劳动合同") && text.contains("解除")) {
            return "【大白话】这条主要在琢磨：单位或员工能不能解约、解约理由站不站得住脚，以及解约后要不要给补偿或赔偿。"
                    + "实务里一般要把理由、证据和通知送达程序一块儿核对。";
        }
        if (text.contains("经济补偿")) {
            return "【大白话】这条说的是：哪些情况下解除或终止劳动关系，单位需要给员工经济补偿。"
                    + "补偿通常跟工作年限和工资标准挂钩，具体算法还得看条文和当地实践。";
        }
        if (text.contains("借款") || text.contains("利率") || text.contains("利息")) {
            return "【大白话】这条管的是借钱利息怎么约定、哪些利息法院更可能支持。"
                    + "简单讲，可以谈利息，但超过法律保护上限的部分，往往很难要回来。";
        }
        if (text.contains("合同") && text.contains("违约")) {
            return "【大白话】这条讲的是：一方没按合同办事时，可能要承担啥责任。"
                    + "常见的就是继续履行、修理退换、少付钱或者赔钱这类后果。";
        }
        if (text.contains("侵权") || text.contains("损害赔偿")) {
            return "【大白话】这条多半是在说：给别人造成损害要不要赔、赔多少。"
                    + "要不要赔通常看过错、因果关系和损失证据齐不齐。";
        }
        if (text.contains("期限") || text.contains("时效")) {
            return "【大白话】这条重点在时间：期限怎么算、过了点会不会影响维权。"
                    + "起算日、中止中断这些细节还得对着事实慢慢抠。";
        }
        if (text.contains("应当") || text.contains("必须")) {
            String g = firstGistSentence(effective, 130);
            String gist = StringUtils.hasText(g) ? LawInterpretationTexts.normalizeCjkInline(g) : "";
            String quote = StringUtils.hasText(gist)
                    ? ("条文里写的是：" + gist + (gist.matches(".*[。！？…]$") ? "" : "。"))
                    : "";
            return "【大白话】这条带「应当」「必须」字样时，多是在强调义务——换句话说，按规定你得这么做。"
                    + quote
                    + "具体谁能管、怎么追责，还得看整部法和配套规定。";
        }
        if (text.contains("不得") || text.contains("禁止")) {
            String g = firstGistSentence(effective, 130);
            String gist = StringUtils.hasText(g) ? LawInterpretationTexts.normalizeCjkInline(g) : "";
            String quote = StringUtils.hasText(gist)
                    ? ("原文大意：" + gist + (gist.matches(".*[。！？…]$") ? "" : "。"))
                    : "";
            return "【大白话】出现「不得」「禁止」，就是在划红线：这类事别做，做了可能违法或无效。" + quote;
        }

        String g = firstGistSentence(effective, 180);
        if (!StringUtils.hasText(g)) {
            return "【大白话】系统暂时没有识别到清晰的核心法律内容，建议打开来源页面后重点找「应当、可以、不得、承担、除外」这类关键词，再结合案由和证据判断。";
        }
        String gist = LawInterpretationTexts.normalizeCjkInline(g);
        String tail = "适用时别忘了看有没有「但书」「除外」或特别法优先的情况。";
        boolean endsSentence = gist.matches(".*[。！？…]$");
        return "【大白话】" + gist + (endsSentence ? "" : "。") + tail;
    }

    /**
     * 从原始正文中抽取更适合解读的“有效法律内容”：优先保留权利、义务、禁止、条件、后果、程序、期限等句子，
     * 跳过目录、题注、制定目的、沿革说明等对法律小白帮助不大的文本。
     */
    public static String effectiveLegalContent(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String cleaned = stripTechnicalNoise(content.replace('\u00A0', ' '));
        String normalized = cleaned.replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        normalized = normalized.replaceFirst("^法规目录[:：]\\s*", "")
                .replaceFirst("^法规索引[:：]\\s*", "");
        List<String> sentences = splitSentences(normalized);
        List<String> candidates = sentences.stream()
                .filter(LawArticleDigestHelper::isEffectiveSentence)
                .sorted(Comparator.comparingInt(LawArticleDigestHelper::sentencePriority).reversed())
                .limit(4)
                .toList();
        if (!candidates.isEmpty()) {
            return LawInterpretationTexts.normalizeCjkInline(String.join("", restoreOriginalOrder(sentences, candidates)));
        }
        return LawInterpretationTexts.normalizeCjkInline(sentences.stream()
                .filter(s -> !isNoiseSentence(s))
                .limit(3)
                .reduce("", String::concat));
    }

    public static String inferScenarios(String lawName, String content) {
        String text = (lawName == null ? "" : lawName) + (content == null ? "" : content);
        List<String> scenarios = new ArrayList<>();
        scenarios.add(inferPrimaryCause(lawName, content));
        if (text.contains("解除") && text.contains("劳动合同")) {
            scenarios.add("劳动合同解除争议");
        }
        if (text.contains("经济补偿") || text.contains("赔偿金")) {
            scenarios.add("经济补偿与赔偿核算");
        }
        if (text.contains("利率") || text.contains("利息")) {
            scenarios.add("利息与利率争议");
        }
        if (text.contains("违约")) {
            scenarios.add("违约责任主张");
        }
        if (text.contains("居住") || text.contains("户口") || text.contains("流动")) {
            scenarios.add("户籍与居住证件办理");
        }
        scenarios.add("法条对照引用");
        scenarios.add("法规现行效力核对");
        return String.join("、", scenarios);
    }

    public static String inferExceptionsText(String lawName, String content) {
        String text = (lawName == null ? "" : lawName) + (content == null ? "" : content);
        List<String> exceptions = new ArrayList<>();
        if (text.contains("除外") || text.contains("但是") || text.contains("但书") || text.contains("除外情形")) {
            exceptions.add("条文里若有「但是」「除外」，通常表示在特定条件下本条不适用或效果会变，要先抠清楚例外条件。");
        }
        if (text.contains("劳动合同")) {
            exceptions.add("劳动争议还需核对规章制度是否经过民主程序、是否公示告知，以及解除通知与证据链是否完整。");
        }
        if (text.contains("借款") || text.contains("利率") || text.contains("利息")) {
            exceptions.add("借贷利率司法保护上限会随合同成立时间与一年期 LPR 调整而变化，不同时点要分开算。");
        }
        if (text.contains("合同")) {
            exceptions.add("合同是否成立生效、是否存在不可抗力或免责条款，会直接影响能否主张违约责任。");
        }
        exceptions.add("数据库摘录仅供参考，正式引用请以权威公布文本及最新修订为准。");
        return String.join(" ", exceptions);
    }

    public static String inferCauseOfAction(String lawName, String content) {
        String text = (lawName == null ? "" : lawName) + (content == null ? "" : content);
        List<String> causes = new ArrayList<>();
        if (text.contains("劳动合同") || text.contains("工资")) {
            causes.add("劳动争议");
        }
        if (text.contains("借款") || text.contains("利率")) {
            causes.add("民间借贷纠纷");
        }
        if (text.contains("合同") || text.contains("违约")) {
            causes.add("合同纠纷");
        }
        if (text.contains("离婚") || text.contains("抚养")) {
            causes.add("婚姻家庭纠纷");
        }
        if (text.contains("侵权") || text.contains("损害赔偿")) {
            causes.add("侵权责任纠纷");
        }
        if (text.contains("房屋") || text.contains("建设工程")) {
            causes.add("不动产与建设工程纠纷");
        }
        return causes.isEmpty() ? "法律法规检索" : String.join("、", causes);
    }

    private static String inferPrimaryCause(String lawName, String content) {
        return inferCauseOfAction(lawName, content).split("、")[0];
    }

    private static String shortLawTopic(String lawName) {
        if (!StringUtils.hasText(lawName)) {
            return "该领域";
        }
        String t = lawName.replaceFirst("^《", "").replaceFirst("》$", "").trim();
        return t.length() > 24 ? t.substring(0, 24) + "…" : t;
    }

    private static List<String> splitSentences(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : text.split("(?<=[。！？；;])")) {
            String s = part.trim();
            if (s.length() > 0) {
                out.add(s);
            }
        }
        if (out.isEmpty()) {
            out.add(text.trim());
        }
        return out;
    }

    private static boolean isEffectiveSentence(String s) {
        if (!StringUtils.hasText(s) || isNoiseSentence(s)) {
            return false;
        }
        String[] signals = {
                "应当", "必须", "可以", "有权", "不得", "禁止", "承担", "负责", "赔偿", "补偿", "支付",
                "解除", "终止", "无效", "有效", "申请", "受理", "审查", "决定", "处罚", "罚款", "责任",
                "除外", "但是", "期限", "期间", "利率", "利息", "合同", "劳动者", "用人单位", "人民法院"
        };
        return Arrays.stream(signals).anyMatch(s::contains);
    }

    private static boolean isNoiseSentence(String s) {
        if (!StringUtils.hasText(s)) {
            return true;
        }
        String text = s.trim();
        if (text.length() < 8) {
            return true;
        }
        if (text.matches("(?i).*(?:\\.docx|\\.pdf|\\.ofd|/prod/\\d{8}/|flk\\.npc\\.gov\\.cn|https?://).*")) {
            return true;
        }
        if (text.matches(".*(目录|题注|公布日期|施行日期|制定机关|法律效力位阶|时效性)[:：]?.*") && text.length() < 80) {
            return true;
        }
        if (text.matches("第[一二三四五六七八九十百千万零〇两0-9]+章.*")
                || text.matches("第[一二三四五六七八九十百千万零〇两0-9]+节.*")) {
            return true;
        }
        return (text.startsWith("为了") || text.startsWith("根据")) && text.length() < 90
                && !isEffectiveSentenceWithoutNoiseCheck(text);
    }

    private static boolean isEffectiveSentenceWithoutNoiseCheck(String s) {
        String[] strongSignals = {"应当", "必须", "不得", "禁止", "承担", "赔偿", "补偿", "支付", "责任"};
        return Arrays.stream(strongSignals).anyMatch(s::contains);
    }

    private static int sentencePriority(String s) {
        int score = 0;
        String[] strong = {"应当", "必须", "不得", "禁止", "承担", "赔偿", "补偿", "支付", "责任", "无效"};
        String[] medium = {"可以", "有权", "解除", "终止", "申请", "受理", "期限", "但是", "除外"};
        for (String x : strong) {
            if (s.contains(x)) score += 3;
        }
        for (String x : medium) {
            if (s.contains(x)) score += 2;
        }
        if (s.length() >= 24 && s.length() <= 180) score += 2;
        if (s.startsWith("为了") || s.startsWith("本法")) score -= 3;
        return score;
    }

    private static List<String> restoreOriginalOrder(List<String> source, List<String> selected) {
        Set<String> picked = new LinkedHashSet<>(selected);
        List<String> out = new ArrayList<>();
        for (String s : source) {
            if (picked.remove(s)) {
                out.add(s);
            }
            if (picked.isEmpty()) {
                break;
            }
        }
        return out;
    }

    /** 取正文开头若干字，尽量停在句号处 */
    private static String firstGistSentence(String content, int maxLen) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String s = content.replaceAll("\\s+", " ").trim();
        if (s.startsWith("法规目录：") || s.startsWith("法规索引：")) {
            int idx = s.indexOf('：');
            if (idx >= 0 && idx + 1 < s.length()) {
                s = s.substring(idx + 1).trim();
            }
        }
        if (s.length() <= maxLen) {
            return LawInterpretationTexts.normalizeCjkInline(s);
        }
        String chunk = s.substring(0, maxLen);
        int dot = Math.max(chunk.lastIndexOf('。'), Math.max(chunk.lastIndexOf('！'), chunk.lastIndexOf('？')));
        if (dot > 40) {
            return LawInterpretationTexts.normalizeCjkInline(chunk.substring(0, dot + 1));
        }
        return LawInterpretationTexts.normalizeCjkInline(chunk + "…");
    }
}
