package com.fatongai.legalassistant.law.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LawArticleCitationNormalizerTest {

    @Test
    void parsesArabicArticleReferenceWithLawAlias() {
        var parsed = LawArticleCitationNormalizer.parse("民法典第577条");

        assertThat(parsed.lawNameHint()).isEqualTo("中华人民共和国民法典");
        assertThat(parsed.articleOrdinal()).isEqualTo(577);
        assertThat(parsed.normalizedArticleNo()).isEqualTo("第577条");
    }

    @Test
    void parsesChineseArticleReference() {
        var parsed = LawArticleCitationNormalizer.parse("《劳动合同法》第三十九条");

        assertThat(parsed.lawNameHint()).isEqualTo("中华人民共和国劳动合同法");
        assertThat(parsed.articleOrdinal()).isEqualTo(39);
        assertThat(parsed.normalizedArticleNo()).isEqualTo("第39条");
    }

    @Test
    void parsesArticleOnlyReference() {
        var parsed = LawArticleCitationNormalizer.parse("第八十二条");

        assertThat(parsed.lawNameHint()).isBlank();
        assertThat(parsed.articleOrdinal()).isEqualTo(82);
        assertThat(parsed.hasArticleNo()).isTrue();
    }
}
