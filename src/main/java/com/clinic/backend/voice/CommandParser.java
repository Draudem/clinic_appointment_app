package com.clinic.backend.voice;

/**
 * Портовый контракт разбора фразы в команду. Реализации:
 *   - NluCommandParser  — локальная ONNX-модель (быстро, офлайн);
 *   - RuleCommandParser — детерминированные правила (fallback);
 *   - RagLlmCommandParser — RAG + LLM (Ollama), когда включён llm.enabled.
 * Выбор — свойством app.voice.parser, код вызывающих не меняется.
 */
public interface CommandParser {

    ParsedCommand parse(String text);

    /** Имя движка (nlu/rule/rag-llm) для диагностики. */
    String name();
}
