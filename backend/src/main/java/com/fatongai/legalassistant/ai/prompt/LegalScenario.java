package com.fatongai.legalassistant.ai.prompt;

public enum LegalScenario {
    LABOR("劳动纠纷"),
    MARRIAGE("婚姻家事"),
    CONSUMER("消费维权"),
    CONTRACT("合同纠纷"),
    GENERAL("综合咨询");

    private final String label;

    LegalScenario(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
