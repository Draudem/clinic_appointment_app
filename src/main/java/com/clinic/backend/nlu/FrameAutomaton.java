package com.clinic.backend.nlu;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Точный порт FrameAutomaton из мобильного приложения (Kotlin) —
 * а тот, в свою очередь, порт train/constrained.py модели коллеги.
 * Автомат гарантирует синтаксически валидный фрейм всегда: модель выбирает
 * только значения, структуру — автомат.
 */
public class FrameAutomaton {

    private enum S { INTENT, SPECIALTY, TIME, FACTORS, SYMPTOMS, CLARIF, END_FRAME, DONE }

    private final Map<String, Integer> idOf;
    private S state = S.INTENT;
    private int lastRank = -1;
    private int emitted = 0;
    private final Set<String> symptoms = new HashSet<>();
    private final Map<Integer, String> tokenById;

    public FrameAutomaton(Map<String, Integer> idOf) {
        this.idOf = idOf;
        this.tokenById = buildTokenById(idOf);
    }

    private Map<Integer, String> buildTokenById(Map<String, Integer> idOf) {
        Map<Integer, String> map = new java.util.HashMap<>();
        for (String t : FrameVocab.allFrameTokens()) {
            Integer id = idOf.get(t);
            if (id != null) {
                map.put(id, t);
            }
        }
        return map;
    }

    private Set<Integer> ids(Iterable<String> tokens) {
        Set<Integer> result = new HashSet<>();
        for (String t : tokens) {
            Integer id = idOf.get(t);
            if (id != null) {
                result.add(id);
            }
        }
        return result;
    }

    /** Допустимые токены на текущем шаге. */
    public Set<Integer> allowed() {
        return switch (state) {
            case INTENT -> ids(FrameVocab.INTENTS);
            case END_FRAME -> Set.of(idOf.get("<END_FRAME>"));
            case SPECIALTY -> ids(FrameVocab.SPECIALTIES);
            case TIME -> ids(List.of("<T_UNKNOWN>", "<TIME>"));
            case FACTORS -> {
                List<String> ok = new ArrayList<>();
                for (String t : FrameVocab.TIME_SCOPE) {
                    if (FrameVocab.rank(t) > lastRank) {
                        ok.add(t);
                    }
                }
                for (String t : FrameVocab.TIME_DAY) {
                    if (FrameVocab.rank(t) > lastRank) {
                        ok.add(t);
                    }
                }
                for (String t : FrameVocab.TIME_OF_DAY) {
                    if (FrameVocab.rank(t) > lastRank) {
                        ok.add(t);
                    }
                }
                Set<Integer> result = new HashSet<>(ids(ok));
                if (emitted > 0) {
                    result.add(idOf.get("</TIME>"));
                }
                yield result;
            }
            case SYMPTOMS -> {
                List<String> rest = new ArrayList<>();
                for (String s : FrameVocab.SYMPTOMS) {
                    if (!symptoms.contains(s)) {
                        rest.add(s);
                    }
                }
                Set<Integer> result = new HashSet<>(ids(rest));
                result.add(idOf.get("<NEEDS_CLARIFICATION>"));
                result.add(idOf.get("<END_FRAME>"));
                yield result;
            }
            case CLARIF -> Set.of(idOf.get("<END_FRAME>"));
            case DONE -> Set.of(idOf.get("<EOS>"));
        };
    }

    /** Переход автомата после выбранного токена. */
    public void step(int id) {
        String tok = tokenById.get(id);
        if (tok == null) {
            state = S.DONE;
            return;
        }
        state = switch (state) {
            // «пустые» интенты (UNSUPPORTED, голые да/нет) сразу заканчивают фрейм:
            // тянуть за ними специальность бессмысленно — её в ответе нет
            case INTENT -> FrameVocab.BARE_INTENTS.contains(tok) ? S.END_FRAME : S.SPECIALTY;
            case SPECIALTY -> S.TIME;
            case TIME -> tok.equals("<T_UNKNOWN>") ? S.SYMPTOMS : S.FACTORS;
            case FACTORS -> {
                if (tok.equals("</TIME>")) {
                    yield S.SYMPTOMS;
                } else {
                    lastRank = FrameVocab.rank(tok);
                    emitted++;
                    yield S.FACTORS;
                }
            }
            case SYMPTOMS -> {
                if (symptoms.contains(tok)) {
                    yield S.SYMPTOMS;
                } else if (tok.equals("<NEEDS_CLARIFICATION>")) {
                    yield S.CLARIF;
                } else {
                    if (tok.startsWith("<SYMPTOM_")) {
                        symptoms.add(tok);
                    }
                    if (tok.equals("<END_FRAME>")) {
                        yield S.DONE;
                    } else {
                        yield S.SYMPTOMS;
                    }
                }
            }
            case CLARIF -> tok.equals("<END_FRAME>") ? S.DONE : S.CLARIF;
            case END_FRAME -> tok.equals("<END_FRAME>") ? S.DONE : S.END_FRAME;
            case DONE -> S.DONE;
        };
    }

    public boolean isDone() {
        return state == S.DONE;
    }
}
