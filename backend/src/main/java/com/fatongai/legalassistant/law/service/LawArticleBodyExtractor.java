package com.fatongai.legalassistant.law.service;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从整页或整篇文本中截取「单条」法条正文，避免把目录、序言、立法目的等当作本条解读依据。
 */
public final class LawArticleBodyExtractor {

    /**
     * 与 NPC 详情拆分、页面抓取一致：允许「第 一 条」「第1条」等与站点 HTML 转写一致的空白。
     */
    public static final Pattern ARTICLE_HEADING = Pattern.compile(
            "(第\\s*[一二三四五六七八九十百千万零〇两0-9]+\\s*条(?:之一|之二|之三|之四|之五)?)");

    private LawArticleBodyExtractor() {
    }

    public static String normalizePlainText(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.replace('\u00A0', ' ')
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    /**
     * 去掉「第一条」之前的说明性、立法过程类前缀（国家库 JSON 常把审议说明等排在正文前）。
     * 不改变已以「第…条」开头的文本。
     */
    public static String trimLeadingBeforeFirstArticleHeading(String text) {
        String t = normalizePlainText(text);
        if (!StringUtils.hasText(t)) {
            return t;
        }
        Matcher m = ARTICLE_HEADING.matcher(t);
        if (m.find() && m.start() > 0) {
            return t.substring(m.start()).trim();
        }
        return t;
    }

    /**
     * 截取单条条文正文；「全文」类从首条起掐掉前文。
     *
     * @param fullText   页面或库内全文
     * @param articleNo  条号，如「第三十二条」
     * @param maxChars   送入模型的最大字符数
     */
    public static String extractArticleBody(String fullText, String articleNo, int maxChars) {
        String text = normalizePlainText(fullText);
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String art = articleNo == null ? "" : articleNo.trim();
        if ("全文".equals(art) || art.contains("全文")) {
            return clamp(textFromFirstArticleHeading(text), maxChars);
        }
        Optional<String> slice = findArticleSlice(text, art);
        if (slice.isPresent()) {
            return clamp(slice.get(), maxChars);
        }
        String tail = sliceFromHeadingIndex(text, art);
        if (StringUtils.hasText(tail) && tail.length() >= 12) {
            return clamp(tail, maxChars);
        }
        return clamp(text, maxChars);
    }

    /** 从首处「第…条」起截断，去掉此前的序言、目录等 */
    public static String textFromFirstArticleHeading(String text) {
        String t = trimLeadingBeforeFirstArticleHeading(text);
        Matcher m = ARTICLE_HEADING.matcher(t);
        if (m.find()) {
            return t.substring(m.start()).trim();
        }
        return stripLeadingCatalogOrPreamble(t);
    }

    private static String stripLeadingCatalogOrPreamble(String text) {
        String t = text;
        if (t.startsWith("法规目录：") || t.startsWith("法规索引：")) {
            int idx = t.indexOf('：');
            if (idx >= 0 && idx + 1 < t.length()) {
                t = t.substring(idx + 1).trim();
            }
        }
        int cut = indexOfPreambleCut(t);
        if (cut > 0 && cut < t.length() / 2) {
            t = t.substring(cut).trim();
        }
        return t;
    }

    /**
     * 若开头明显为整部法律的立法目的/依据而非具体条文，尽量跳过（启发式）。
     */
    private static int indexOfPreambleCut(String t) {
        if (t.length() < 80) {
            return 0;
        }
        String head = t.substring(0, Math.min(400, t.length()));
        if ((head.startsWith("为了") || head.startsWith("根据") || head.contains("制定本法") || head.contains("制定本条例"))
                && head.indexOf("第") > 0) {
            int i = head.indexOf("第一章");
            if (i > 20) {
                return i;
            }
        }
        return 0;
    }

    public static Optional<String> findArticleSlice(String text, String requestedArticleNo) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(requestedArticleNo)) {
            return Optional.empty();
        }
        Matcher m = ARTICLE_HEADING.matcher(text);
        List<Integer> starts = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
            headings.add(m.group(1));
        }
        if (starts.isEmpty()) {
            return Optional.empty();
        }
        for (int i = 0; i < headings.size(); i++) {
            if (headingsMatch(headings.get(i), requestedArticleNo.trim())) {
                int start = starts.get(i);
                int end = i + 1 < starts.size() ? starts.get(i + 1) : text.length();
                String slice = text.substring(start, end).trim();
                if (slice.length() >= 6) {
                    return Optional.of(slice);
                }
            }
        }
        return Optional.empty();
    }

    static boolean headingsMatch(String headingInText, String requested) {
        if (headingInText == null || requested == null) {
            return false;
        }
        String h = headingInText.replaceAll("\\s+", "");
        String r = requested.replaceAll("\\s+", "");
        if (h.equals(r)) {
            return true;
        }
        OptionalInt oh = parseArticleOrdinal(h);
        OptionalInt or = parseArticleOrdinal(r);
        return oh.isPresent() && or.isPresent() && oh.getAsInt() == or.getAsInt();
    }

    /** 解析「第三十二条」「第32条」等为序数，无法解析则 empty */
    static OptionalInt parseArticleOrdinal(String headingWithOptionalDiTiao) {
        String s = headingWithOptionalDiTiao == null ? "" : headingWithOptionalDiTiao.replaceAll("\\s+", "").trim();
        Matcher arab = Pattern.compile("^第(\\d+)条").matcher(s);
        if (arab.find()) {
            return OptionalInt.of(Integer.parseInt(arab.group(1)));
        }
        Matcher cn = Pattern.compile("^第(.+?)条").matcher(s);
        if (!cn.find()) {
            return OptionalInt.empty();
        }
        String inner = cn.group(1).trim();
        int v = parseChineseCardinal(inner);
        return v > 0 ? OptionalInt.of(v) : OptionalInt.empty();
    }

    /**
     * 中文基数词（条内数字部分），支持 1～999 常见写法及「之一」等后缀前的数字段。
     */
    private static int parseChineseCardinal(String inner) {
        for (String suf : List.of("之一", "之二", "之三", "之四", "之五")) {
            if (inner.endsWith(suf)) {
                inner = inner.substring(0, inner.length() - suf.length()).trim();
                break;
            }
        }
        if (inner.matches("\\d+")) {
            return Integer.parseInt(inner.replaceFirst("^0+", ""));
        }
        return chineseToInt(inner);
    }

    private static int chineseToInt(String s) {
        if (!StringUtils.hasText(s)) {
            return -1;
        }
        if (s.contains("千")) {
            return -1;
        }
        if (s.contains("百")) {
            return parseHundreds(s);
        }
        if (s.contains("十")) {
            return parseTens(s);
        }
        int d = singleCnDigit(s.charAt(0));
        return d >= 0 && s.length() == 1 ? d : -1;
    }

    private static int parseTens(String s) {
        if (s.equals("十")) {
            return 10;
        }
        if (s.startsWith("十")) {
            int tail = s.length() > 1 ? singleCnDigit(s.charAt(1)) : 0;
            return tail >= 0 ? 10 + tail : 10;
        }
        if (s.endsWith("十")) {
            int head = singleCnDigit(s.charAt(0));
            return head >= 0 ? head * 10 : -1;
        }
        int idx = s.indexOf('十');
        if (idx <= 0) {
            return -1;
        }
        int tens = singleCnDigit(s.charAt(0));
        int ones = idx + 1 < s.length() ? singleCnDigit(s.charAt(idx + 1)) : 0;
        if (tens < 0) {
            return -1;
        }
        return tens * 10 + Math.max(ones, 0);
    }

    private static int parseHundreds(String s) {
        int idx = s.indexOf('百');
        if (idx < 0) {
            return -1;
        }
        int hundreds = idx == 0 ? 1 : singleCnDigit(s.charAt(0));
        if (hundreds < 0) {
            hundreds = 1;
        }
        String rest = s.substring(idx + 1);
        if (rest.isEmpty()) {
            return hundreds * 100;
        }
        if (rest.startsWith("十") || rest.contains("十")) {
            return hundreds * 100 + parseTens(rest);
        }
        if (rest.length() == 1) {
            int u = singleCnDigit(rest.charAt(0));
            return hundreds * 100 + Math.max(u, 0);
        }
        return hundreds * 100 + chineseToInt(rest);
    }

    private static int singleCnDigit(char c) {
        return switch (c) {
            case '零', '〇' -> 0;
            case '一' -> 1;
            case '二', '两' -> 2;
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

    private static String sliceFromHeadingIndex(String text, String articleNo) {
        String key = articleNo.trim();
        int idx = text.indexOf(key);
        if (idx < 0) {
            idx = text.indexOf(key.replace(" ", ""));
        }
        if (idx < 0) {
            return "";
        }
        int next = findNextArticleStart(text, idx + key.length());
        return text.substring(idx, next).trim();
    }

    private static int findNextArticleStart(String text, int from) {
        Matcher m = ARTICLE_HEADING.matcher(text);
        while (m.find()) {
            if (m.start() >= from) {
                return m.start();
            }
        }
        return text.length();
    }

    public static String clamp(String s, int maxChars) {
        if (s == null || maxChars <= 0) {
            return "";
        }
        if (s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, maxChars) + "…";
    }
}
