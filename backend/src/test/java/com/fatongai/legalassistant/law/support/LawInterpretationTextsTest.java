package com.fatongai.legalassistant.law.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LawInterpretationTextsTest {

    @Test
    void extractCore_stripsBundledSections() {
        String raw = "要点：申请须缴费。\n\n适用：法条检索\n\n例外：以官方为准\n\n风险提示：不替代律师。";
        assertEquals("申请须缴费。", LawInterpretationTexts.extractCoreInterpretation(raw));
    }

    @Test
    void extractCore_stripsLeadingTags() {
        assertEquals("核心。", LawInterpretationTexts.extractCoreInterpretation("【大白话】核心。"));
        assertEquals("正文", LawInterpretationTexts.extractCoreInterpretation("要点：正文"));
    }

    @Test
    void extractCore_empty() {
        assertTrue(LawInterpretationTexts.extractCoreInterpretation(null).isEmpty());
        assertTrue(LawInterpretationTexts.extractCoreInterpretation("   ").isEmpty());
    }

    @Test
    void normalizeCjk_removesSpuriousSpacesBetweenHan() {
        assertEquals(
                "人才强国战略科学技术普及",
                LawInterpretationTexts.normalizeCjkInline("人才强国 战略科学 技术普 及"));
    }

    @Test
    void normalizeCjk_collapsesDuplicateFullStops() {
        assertEquals("本法。适用", LawInterpretationTexts.normalizeCjkInline("本法。。适用"));
    }

    @Test
    void extractCore_stripsLegacyDigestLeadIn() {
        String raw = "【大白话】针对「第一条」，先把法言法语放一边，本条的核心意思可以概括成：第一条 为了本法。。适用时别忘了";
        String out = LawInterpretationTexts.extractCoreInterpretation(raw);
        assertFalse(out.contains("先把法言法语"));
        assertFalse(out.contains("。。"));
        assertTrue(out.contains("适用时别忘了"));
    }
}
