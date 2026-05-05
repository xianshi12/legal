package com.fatongai.legalassistant.law.service;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 条文引用规范化：把「民法典第577条」「《劳动合同法》第三十九条」等输入归一到法律名线索和条号序号。
 */
public final class LawArticleCitationNormalizer {
    private static final Pattern BRACKETED_LAW = Pattern.compile("《([^》]{2,80})》");
    private static final Pattern ARTICLE_REF = Pattern.compile(
            "(?:第\\s*)?([一二三四五六七八九十百千万零〇两0-9]+)\\s*条(?:之([一二三四五六七八九十0-9]+))?");
    private static final Map<String, String> LAW_ALIASES = new LinkedHashMap<>();

    static {
        LAW_ALIASES.put("民法典", "中华人民共和国民法典");
        LAW_ALIASES.put("劳动合同法", "中华人民共和国劳动合同法");
        LAW_ALIASES.put("劳动法", "中华人民共和国劳动法");
        LAW_ALIASES.put("公司法", "中华人民共和国公司法");
        LAW_ALIASES.put("刑法", "中华人民共和国刑法");
        LAW_ALIASES.put("民事诉讼法", "中华人民共和国民事诉讼法");
        LAW_ALIASES.put("行政诉讼法", "中华人民共和国行政诉讼法");
        LAW_ALIASES.put("道路交通安全法", "中华人民共和国道路交通安全法");
        LAW_ALIASES.put("消费者权益保护法", "中华人民共和国消费者权益保护法");
        LAW_ALIASES.put("个人信息保护法", "中华人民共和国个人信息保护法");
    }

    private LawArticleCitationNormalizer() {
    }

    public static NormalizedCitation parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return NormalizedCitation.empty();
        }
        String input = raw.trim();
        Matcher articleMatcher = ARTICLE_REF.matcher(input.replaceAll("\\s+", ""));
        Integer ordinal = null;
        String normalizedArticleNo = "";
        if (articleMatcher.find()) {
            ordinal = parseOrdinal(articleMatcher.group(1));
            if (ordinal != null) {
                normalizedArticleNo = "第" + ordinal + "条";
                if (StringUtils.hasText(articleMatcher.group(2))) {
                    Integer sub = parseOrdinal(articleMatcher.group(2));
                    if (sub != null) {
                        normalizedArticleNo += "之" + sub;
                    }
                }
            }
        }

        String lawHint = extractLawHint(input, articleMatcher);
        return new NormalizedCitation(lawHint, normalizedArticleNo, ordinal, ordinal != null);
    }

    public static String normalizeArticleNo(String articleNo) {
        NormalizedCitation parsed = parse(articleNo);
        return parsed.hasArticleNo() ? parsed.normalizedArticleNo() : stripArticleNoise(articleNo);
    }

    public static Integer parseArticleOrdinal(String articleNo) {
        return parse(articleNo).articleOrdinal();
    }

    public static String stableKey(String lawName, String articleNo) {
        String law = lawName == null ? "" : lawName.trim();
        String no = normalizeArticleNo(articleNo);
        return (law + "#" + no).toLowerCase(Locale.ROOT);
    }

    private static String extractLawHint(String input, Matcher articleMatcher) {
        Matcher bracket = BRACKETED_LAW.matcher(input);
        if (bracket.find()) {
            return expandLawAlias(bracket.group(1));
        }
        int articleStart = articleMatcher != null && articleMatcher.find(0) ? articleMatcher.start() : -1;
        String prefix = articleStart > 0 ? input.substring(0, articleStart) : input;
        prefix = prefix.replaceAll("[《》\\s第条0-9一二三四五六七八九十百千万零〇两之]+$", "").trim();
        if (!StringUtils.hasText(prefix)) {
            for (String alias : LAW_ALIASES.keySet()) {
                if (input.contains(alias)) {
                    return expandLawAlias(alias);
                }
            }
            return "";
        }
        return expandLawAlias(prefix);
    }

    private static String expandLawAlias(String law) {
        if (!StringUtils.hasText(law)) {
            return "";
        }
        String s = law.trim().replace("中华人民共和国中华人民共和国", "中华人民共和国");
        for (Map.Entry<String, String> entry : LAW_ALIASES.entrySet()) {
            if (s.equals(entry.getKey()) || s.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return s;
    }

    private static Integer parseOrdinal(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String s = raw.trim();
        if (s.matches("\\d+")) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return parseChineseNumber(s);
    }

    private static Integer parseChineseNumber(String raw) {
        String s = raw.replace('〇', '零').replace('两', '二');
        int result = 0;
        int section = 0;
        int number = 0;
        for (int i = 0; i < s.length(); i++) {
            int digit = digitValue(s.charAt(i));
            if (digit >= 0) {
                number = digit;
                continue;
            }
            int unit = unitValue(s.charAt(i));
            if (unit == 10000) {
                section = (section + number) * unit;
                result += section;
                section = 0;
                number = 0;
            } else if (unit > 0) {
                section += (number == 0 ? 1 : number) * unit;
                number = 0;
            }
        }
        int parsed = result + section + number;
        return parsed > 0 ? parsed : null;
    }

    private static int digitValue(char c) {
        return switch (c) {
            case '零' -> 0;
            case '一' -> 1;
            case '二' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> -1;
        };
    }

    private static int unitValue(char c) {
        return switch (c) {
            case '十' -> 10;
            case '百' -> 100;
            case '千' -> 1000;
            case '万' -> 10000;
            default -> -1;
        };
    }

    private static String stripArticleNoise(String s) {
        if (!StringUtils.hasText(s)) {
            return "";
        }
        return s.trim().replaceAll("\\s+", "").replace("第", "").replace("条", "");
    }

    public record NormalizedCitation(
            String lawNameHint,
            String normalizedArticleNo,
            Integer articleOrdinal,
            boolean hasArticleNo
    ) {
        static NormalizedCitation empty() {
            return new NormalizedCitation("", "", null, false);
        }
    }
}
