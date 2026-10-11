package com.clinic.backend.voice;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки голосового контура (app.voice.*).
 * parser: nlu (локальная ONNX-модель) | rule (правила) | rag-llm (Ollama).
 */
@ConfigurationProperties(prefix = "app.voice")
public class VoiceProperties {

    private String parser = "nlu";

    /** Максимум обменов репликами, после которого диалог уходит оператору. */
    private int maxExchanges = 5;

    public String getParser() {
        return parser;
    }

    public void setParser(String parser) {
        this.parser = parser;
    }

    public int getMaxExchanges() {
        return maxExchanges;
    }

    public void setMaxExchanges(int maxExchanges) {
        this.maxExchanges = maxExchanges;
    }
}
