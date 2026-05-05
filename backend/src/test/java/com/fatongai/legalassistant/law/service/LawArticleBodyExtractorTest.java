package com.fatongai.legalassistant.law.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LawArticleBodyExtractorTest {

    @Test
    void extractsArticleBodyInsteadOfHeadingCatalog() {
        String text = """
                第一条
                为了保护劳动者的合法权益，调整劳动关系，建立和维护适应社会主义市场经济的劳动制度，促进经济发展和社会进步，根据宪法，制定本法。
                第二条
                在中华人民共和国境内的企业、个体经济组织和与之形成劳动关系的劳动者，适用本法。
                第三条
                劳动者享有平等就业和选择职业的权利。
                """;

        String body = LawArticleBodyExtractor.extractArticleBody(text, "第二条", 1000);

        assertThat(body).contains("第二条");
        assertThat(body).contains("适用本法");
        assertThat(body).doesNotContain("第三条");
    }

    @Test
    void matchesArabicAndChineseArticleNumbers() {
        String text = "第三十九条 劳动者有下列情形之一的，用人单位可以解除劳动合同。第四十条 有下列情形之一的，用人单位提前通知。";

        String body = LawArticleBodyExtractor.extractArticleBody(text, "第39条", 1000);

        assertThat(body).contains("第三十九条");
        assertThat(body).contains("解除劳动合同");
        assertThat(body).doesNotContain("第四十条");
    }
}
