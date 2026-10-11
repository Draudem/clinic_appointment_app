package com.clinic.backend.llm;

/**
 * Портовый контракт LLM. Реализация сейчас одна — OpenAI-совместимый клиент
 * (подходит и для Ollama, и для любого облака). Подмена на другой провайдер —
 * один бин в LlmConfig, остальной код не трогается.
 */
public interface LlmClient {

    /** Доступна ли LLM (включена конфигом). */
    boolean enabled();

    /** Имя модели для диагностики. */
    String modelName();

    /**
     * Один запрос чата. system — инструкция, user — запрос.
     * @return текст ответа модели
     * @throws LlmUnavailableException если LLM выключена или недоступна
     */
    String chat(String system, String user);
}
