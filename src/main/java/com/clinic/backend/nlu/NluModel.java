package com.clinic.backend.nlu;

/**
 * Портовый контракт NLU: «текст → semantic frame».
 * Реализации: OnnxNluModel (локальная модель коллеги), LlmNluModel (RAG через
 * Ollama, задел), RuleNluModel (детерминированная заглушка).
 * Замена движка = изменение одного бина в конфигурации.
 */
public interface NluModel {

    /** Разбор текста в frame. text может быть STT-текстом «как есть». */
    NluFrame parse(String text);

    /** Название активного движка для диагностики. */
    String name();
}
