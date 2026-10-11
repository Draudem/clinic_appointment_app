package com.clinic.backend.voice;

import com.clinic.backend.llm.LlmClient;
import com.clinic.backend.llm.LlmUnavailableException;
import com.clinic.backend.nlu.FrameVocab;
import com.clinic.backend.rag.ClinicalKnowledgeBase;
import com.clinic.backend.rag.KnowledgeChunk;
import com.clinic.backend.rag.Retriever;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RAG-парсер команд: сначала достаём из базы знаний релевантные чанки
 * (специальности/симптомы), затем просим LLM (Ollama) вернуть команду строгим
 * JSON. Работает поверх локального словаря синонимов — именно это закрывает
 * OOD-случаи, на которых падает нейросетевая NLU.
 *
 * Если LLM выключена или недоступна — тихо делегируем в RuleCommandParser,
 * поэтому этап остаётся рабочим даже без Ollama.
 */
public class RagLlmCommandParser implements CommandParser {

    private final Retriever retriever;
    private final LlmClient llm;
    private final RuleCommandParser fallback;
    private final ObjectMapper mapper = new ObjectMapper();

    public RagLlmCommandParser(Retriever retriever, LlmClient llm, RuleCommandParser fallback) {
        this.retriever = retriever;
        this.llm = llm;
        this.fallback = fallback;
    }

    @Override
    public String name() {
        return "rag-llm";
    }

    @Override
    public ParsedCommand parse(String text) {
        if (llm == null || !llm.enabled()) {
            return withEngine(fallback.parse(text), "rule(llm-off)");
        }
        try {
            String context = buildContext(text);
            String system = "Ты — парсер команд записи к врачу в клинике. "
                    + "Верни ТОЛЬКО JSON без пояснений: "
                    + "{\"intent\":\"BOOK|RESCHEDULE|CANCEL|UNSUPPORTED\","
                    + "\"specialty\":\"название специальности или пусто\","
                    + "\"timeFactors\":[\"T_...\"],\"symptoms\":[\"...\"],"
                    + "\"needsClarification\":true|false}. "
                    + "Допустимые специальности: " + String.join(", ", ClinicalKnowledgeBase.SYNONYMS.keySet()) + ". "
                    + "Допустимые timeFactors: " + allowedTimeFactors() + ". "
                    + "Если запрос не про запись к врачу — intent UNSUPPORTED.";
            String user = "Запрос пациента: " + text + "\n\nРелевантные знания:\n" + context;
            String answer = llm.chat(system, user);
            ParsedCommand parsed = fromJson(text, answer);
            if (parsed != null) {
                return parsed;
            }
            return withEngine(fallback.parse(text), "rule(llm-badjson)");
        } catch (LlmUnavailableException e) {
            return withEngine(fallback.parse(text), "rule(llm-error)");
        }
    }

    private ParsedCommand fromJson(String text, String answer) {
        try {
            int start = answer.indexOf('{');
            int end = answer.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            JsonNode node = mapper.readTree(answer.substring(start, end + 1));
            String intent = node.path("intent").asText("BOOK").toUpperCase(Locale.ROOT);
            String specialty = node.path("specialty").asText("");
            if (specialty.isBlank() || !ClinicalKnowledgeBase.SYNONYMS.containsKey(specialty)) {
                String rag = knowledgeBaseSpecialty(text);
                specialty = rag != null ? rag : specialty;
            }
            List<String> timeFactors = new ArrayList<>();
            for (JsonNode t : node.path("timeFactors")) {
                String f = t.asText();
                if (isKnownTimeFactor(f)) {
                    timeFactors.add(f);
                }
            }
            List<String> symptoms = new ArrayList<>();
            for (JsonNode s : node.path("symptoms")) {
                symptoms.add(s.asText());
            }
            boolean unsupported = "UNSUPPORTED".equals(intent);
            boolean needsClarification = node.path("needsClarification").asBoolean(false)
                    || (!unsupported && (specialty == null || specialty.isBlank()));
            return new ParsedCommand(intent, specialty, timeFactors, symptoms,
                    timeFactors.isEmpty(), needsClarification, unsupported, "(rag-llm)", "rag-llm");
        } catch (Exception e) {
            return null;
        }
    }

    private String knowledgeBaseSpecialty(String text) {
        for (KnowledgeChunk chunk : retriever.search(text, 1)) {
            if ("specialty".equals(chunk.type())) {
                return chunk.title();
            }
        }
        return null;
    }

    private String buildContext(String text) {
        List<KnowledgeChunk> chunks = retriever.search(text, 5);
        if (chunks.isEmpty()) {
            return "(нет совпадений)";
        }
        StringBuilder sb = new StringBuilder();
        for (KnowledgeChunk c : chunks) {
            sb.append("- ").append(c.text()).append('\n');
        }
        return sb.toString();
    }

    private boolean isKnownTimeFactor(String f) {
        return FrameVocab.TIME_SCOPE.contains(f) || FrameVocab.TIME_DAY.contains(f)
                || FrameVocab.TIME_OF_DAY.contains(f) || "T_UNKNOWN".equals(f);
    }

    private String allowedTimeFactors() {
        List<String> all = new ArrayList<>();
        all.addAll(FrameVocab.TIME_SCOPE);
        all.addAll(FrameVocab.TIME_DAY);
        all.addAll(FrameVocab.TIME_OF_DAY);
        return String.join(", ", all);
    }

    private ParsedCommand withEngine(ParsedCommand base, String engine) {
        return new ParsedCommand(base.intent(), base.specialty(), base.timeFactors(),
                base.symptoms(), base.timeUnknown(), base.needsClarification(),
                base.unsupported(), base.frame(), engine);
    }
}
