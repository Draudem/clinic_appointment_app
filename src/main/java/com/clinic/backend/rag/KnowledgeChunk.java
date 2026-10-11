package com.clinic.backend.rag;

import java.util.List;

/**
 * Единица знания RAG. score заполняется ретривером (у «сырых» чанков — 0).
 */
public record KnowledgeChunk(
        String id,
        String type,      // specialty | doctor | instruction
        String title,
        String text,
        List<String> tags,
        double score) {

    public KnowledgeChunk withScore(double newScore) {
        return new KnowledgeChunk(id, type, title, text, tags, newScore);
    }
}
