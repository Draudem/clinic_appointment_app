package com.clinic.backend.rag;

import java.util.List;

/**
 * Портовый контракт поиска по базе знаний. Сейчас — KeywordRetriever
 * (лексический скоринг по БД). Позже подключается векторный ретривер
 * (pgvector/эмбеддинги Ollama) без изменения вызывающего кода.
 */
public interface Retriever {

    /** Топ-k наиболее релевантных чанков для запроса. */
    List<KnowledgeChunk> search(String query, int k);

    /** Название движка поиска для диагностики. */
    String name();
}
