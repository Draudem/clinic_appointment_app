package com.clinic.backend.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Лексический ретривер: скоринг чанков по вхождению слов запроса.
 * Дёшево, работает офлайн и без векторной БД — идеально для текущего сервера.
 * Векторный ретривер позже подключается вместо этого бина.
 */
@Component
public class KeywordRetriever implements Retriever {

    private final ClinicalKnowledgeBase knowledgeBase;

    public KeywordRetriever(ClinicalKnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    @Override
    public String name() {
        return "keyword";
    }

    @Override
    public List<KnowledgeChunk> search(String query, int k) {
        String q = ClinicalKnowledgeBase.normalize(query);
        if (q.isBlank()) {
            return List.of();
        }
        String[] tokens = q.split("[^\\p{L}\\p{N}]+");
        List<KnowledgeChunk> scored = new ArrayList<>();
        for (KnowledgeChunk chunk : knowledgeBase.chunks()) {
            double score = score(chunk, q, tokens);
            if (score > 0) {
                scored.add(chunk.withScore(score));
            }
        }
        scored.sort(Comparator.comparingDouble(KnowledgeChunk::score).reversed());
        return scored.size() > k ? scored.subList(0, k) : scored;
    }

    private double score(KnowledgeChunk chunk, String fullQuery, String[] tokens) {
        String haystack = (chunk.title() + " " + chunk.text() + " "
                + String.join(" ", chunk.tags())).toLowerCase(Locale.ROOT);
        double score = 0;
        // прямое вхождение всей фразы — сильный сигнал
        if (fullQuery.length() > 3 && haystack.contains(fullQuery)) {
            score += 5;
        }
        for (String token : tokens) {
            if (token.length() < 3) {
                continue;
            }
            if (haystack.contains(token)) {
                score += 1;
            }
        }
        // специальности важнее врачей при подборе по симптому
        if (score > 0 && "specialty".equals(chunk.type())) {
            score += 0.5;
        }
        return score;
    }
}
