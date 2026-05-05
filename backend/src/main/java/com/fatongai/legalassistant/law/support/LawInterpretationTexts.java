package com.fatongai.legalassistant.law.support;

import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * 通俗解读纯文本规范化：与界面分区展示一致，正文区不含「适用/例外/风险提示」等尾随块。
 */
public final class LawInterpretationTexts {

    /**
     * 与 {@link com.fatongai.legalassistant.law.service.LawArticleDigestHelper} 历史模板一致，
     * 去掉后避免与条文原文重复、减少累赘。
     */
    private static final Pattern LEGACY_DIGEST_LEAD_IN = Pattern.compile(
            "^针对「[^」]{0,64}」，先把法言法语放一边，本条的核心意思可以概括成：\\s*");

    private LawInterpretationTexts() {
    }

    /**
     * 去掉 CJK 之间误插入的空格（常见于网页换行/抽字）、全角空格，并合并连续「。」。
     * 条文摘录与通俗解读均可使用。
     */
    public static String normalizeCjkInline(String text) {
        if (!StringUtils.hasText(text)) {
            return text == null ? "" : text.trim();
        }
        String t = text.replace('\u00A0', ' ').replace('\u3000', ' ').trim();
        t = t.replaceAll("[\\t\\x0B\\f\\r]+", " ");
        String prev;
        do {
            prev = t;
            t = t.replaceAll("([\\u4e00-\\u9fff\\u3400-\\u4dbf]) ([\\u4e00-\\u9fff\\u3400-\\u4dbf])", "$1$2");
        } while (!t.equals(prev));
        t = t.replaceAll(" {2,}", " ");
        t = t.replaceAll("。{2,}", "。");
        t = t.replaceAll(" ([，。；、：！？])", "$1");
        t = t.replaceAll("([，。；、：！？]) ", "$1");
        return t.trim();
    }

    /** 去掉规则解读旧版开头的套话（存量数据兼容）。 */
    public static String stripRedundantDigestLeadIn(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String t = text.trim();
        t = LEGACY_DIGEST_LEAD_IN.matcher(t).replaceFirst("");
        return t.trim();
    }

    /** 解读正文展示用：去旧套话 + CJK 空格与标点整理。 */
    public static String normalizeInterpretationDisplay(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return normalizeCjkInline(stripRedundantDigestLeadIn(text));
    }

    /**
     * 从存量或历史拼接文本中取出「通俗解读」正文：去掉前置的「要点：」以及尾随的「适用/例外/风险提示」块。
     */
    public static String extractCoreInterpretation(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String t = text.trim();
        int cut = -1;
        for (String marker : new String[]{"\n\n适用：", "\n\n例外：", "\n\n风险提示："}) {
            int i = t.indexOf(marker);
            if (i >= 0 && (cut < 0 || i < cut)) {
                cut = i;
            }
        }
        if (cut >= 0) {
            t = t.substring(0, cut).trim();
        }
        if (t.startsWith("要点：")) {
            t = t.substring("要点：".length()).trim();
        }
        while (t.startsWith("【大白话】")) {
            t = t.substring("【大白话】".length()).trim();
        }
        return normalizeInterpretationDisplay(t.trim());
    }
}
