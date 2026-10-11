package com.clinic.backend.rag;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Диагностика RAG-этапа:
 *   GET /api/rag/search?q=зуб+стреляет&k=5 -> релевантные чанки базы знаний
 *   GET /api/rag/specialty?q=сыпь+на+коже   -> рекомендованная специальность
 */
@RestController
@RequestMapping("/api/rag")
@Tag(name = "RAG", description = "Поиск по клинической базе знаний (симптомы -> специальность)")
public class RagController {

    private final Retriever retriever;
    private final ClinicalKnowledgeBase knowledgeBase;

    public RagController(Retriever retriever, ClinicalKnowledgeBase knowledgeBase) {
        this.retriever = retriever;
        this.knowledgeBase = knowledgeBase;
    }

    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam("q") String query,
            @RequestParam(value = "k", defaultValue = "5") int k) {
        int limit = Math.min(Math.max(k, 1), 20);
        return Map.of(
                "retriever", retriever.name(),
                "query", query,
                "results", retriever.search(query, limit));
    }

    @GetMapping("/specialty")
    public Map<String, Object> specialty(@RequestParam("q") String query) {
        String recommended = knowledgeBase.recommendSpecialty(query);
        List<KnowledgeChunk> support = retriever.search(
                recommended == null ? query : recommended, 3);
        return Map.of(
                "query", query,
                "specialty", recommended == null ? "" : recommended,
                "support", support);
    }
}
