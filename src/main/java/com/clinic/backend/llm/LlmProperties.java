package com.clinic.backend.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки LLM (app.llm.*). По умолчанию выключено: на текущем сервере
 * (956 MiB RAM) Ollama не помещается вместе с Postgres и JVM. Ollama отдаёт
 * OpenAI-совместимый API, поэтому достаточно указать base-url и модель.
 */
@ConfigurationProperties(prefix = "app.llm")
public class LlmProperties {

    /** Включать ли обращение к LLM. */
    private boolean enabled = false;

    /** Базовый URL OpenAI-совместимого API (Ollama: http://host:11434/v1). */
    private String baseUrl = "http://localhost:11434/v1";

    /** Имя модели, например qwen2.5:1.5b-instruct или llama3.2:3b. */
    private String model = "qwen2.5:1.5b-instruct";

    /** API-ключ (для Ollama не нужен, для внешних сервисов — обязателен). */
    private String apiKey = "";

    private int timeoutSeconds = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
