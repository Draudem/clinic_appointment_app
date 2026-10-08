package com.clinic.assistant.ai.frame;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Структурированный результат разбора речи моделью-парсером.
 *
 * <p>Соответствует выходу модели (см. models/ANDROID_INTEGRATION.md):</p>
 * <pre>
 * &lt;BOOK&gt; &lt;NEUROLOGIST&gt; &lt;TIME&gt; T_TOMORROW T_H10_00 &lt;/TIME&gt; &lt;SYMPTOM_HEADACHE&gt; &lt;END_FRAME&gt;
 * </pre>
 *
 * @param intent             интент (BOOK/RESCHEDULE/CANCEL/UNSUPPORTED)
 * @param specialty          токен специальности модели: GP, NEUROLOGIST, ENT, UNKNOWN_SPECIALTY…
 * @param timeFactors        факторы времени: T_TOMORROW, T_MORNING, T_H10_00, T_UNKNOWN…
 * @param symptoms           симптомы: SYMPTOM_HEADACHE…
 * @param needsClarification модель просит уточнить запрос
 * @param raw                исходная строка фрейма (для журнала и отладки)
 */
public record VoiceFrame(Intent intent,
                         String specialty,
                         List<String> timeFactors,
                         List<String> symptoms,
                         boolean needsClarification,
                         String raw) {

    private static final Set<String> INTENT_TOKENS = Set.of("BOOK", "RESCHEDULE", "CANCEL", "UNSUPPORTED");

    /** Токены специальностей модели (без скобок). */
    private static final Set<String> SPECIALTY_TOKENS = Set.of(
            "GP", "DENTIST", "DERMATOLOGIST", "GYNECOLOGIST", "OPHTHALMOLOGIST",
            "NEUROLOGIST", "PSYCHOLOGIST", "ENT", "UNKNOWN_SPECIALTY");

    /** Фрейм по умолчанию — «не понял». */
    public static VoiceFrame unsupported() {
        return new VoiceFrame(Intent.UNSUPPORTED, null, List.of(), List.of(), true, "<UNSUPPORTED> <END_FRAME>");
    }

    /**
     * Разбирает «сырой» ответ модели — строку токенов.
     * Используется, когда сервис парсера вернул только {@code raw}.
     */
    public static VoiceFrame fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return unsupported();
        }
        Intent intent = null;
        String specialty = null;
        List<String> timeFactors = new ArrayList<>();
        List<String> symptoms = new ArrayList<>();
        boolean clarification = false;

        for (String token : raw.trim().split("\\s+")) {
            String t = stripBrackets(token);
            if (t.isEmpty()) {
                continue;
            }
            if (intent == null && INTENT_TOKENS.contains(t)) {
                intent = Intent.valueOf(t);
                continue;
            }
            switch (t) {
                case "TIME" -> { /* открытие блока времени */ }
                case "/TIME" -> { /* закрытие блока времени */ }
                case "END_FRAME" -> { /* конец фрейма */ }
                case "NEEDS_CLARIFICATION" -> clarification = true;
                case "BOS", "EOS", "USER", "ASSISTANT", "PAD", "UNK" -> { /* служебные */ }
                default -> {
                    if (t.startsWith("SYMPTOM_")) {
                        symptoms.add(t);
                    } else if (t.startsWith("T_")) {
                        timeFactors.add(t);
                    } else if (SPECIALTY_TOKENS.contains(t)) {
                        specialty = t;
                    }
                    // неизвестные токены игнорируем — модель не должна их порождать
                }
            }
        }
        return new VoiceFrame(
                intent == null ? Intent.UNSUPPORTED : intent,
                specialty,
                List.copyOf(timeFactors),
                List.copyOf(symptoms),
                clarification,
                raw.trim());
    }

    /** Собирает строку фрейма из частей (использует заглушка-парсер). */
    public static String buildRaw(Intent intent, String specialty,
                                  List<String> timeFactors, List<String> symptoms,
                                  boolean needsClarification) {
        StringBuilder sb = new StringBuilder();
        sb.append('<').append(intent.name()).append('>');
        if (intent != Intent.UNSUPPORTED && specialty != null) {
            sb.append(" <").append(specialty).append('>');
        }
        if (intent != Intent.UNSUPPORTED && !timeFactors.isEmpty()) {
            sb.append(" <TIME>");
            timeFactors.forEach(f -> sb.append(' ').append(f));
            sb.append(" </TIME>");
        }
        symptoms.forEach(s -> sb.append(" <").append(s).append('>'));
        if (needsClarification) {
            sb.append(" <NEEDS_CLARIFICATION>");
        }
        sb.append(" <END_FRAME>");
        return sb.toString();
    }

    /** Удобный конструктор: raw собирается автоматически. */
    public static VoiceFrame of(Intent intent, String specialty,
                                List<String> timeFactors, List<String> symptoms,
                                boolean needsClarification) {
        return new VoiceFrame(intent, specialty, List.copyOf(timeFactors), List.copyOf(symptoms),
                needsClarification,
                buildRaw(intent, specialty, timeFactors, symptoms, needsClarification));
    }

    /** true, если модель не назвала конкретную специальность. */
    public boolean specialtyUnknown() {
        return specialty == null || "UNKNOWN_SPECIALTY".equals(specialty);
    }

    /** true, если модель не назвала конкретное время. */
    public boolean timeUnknown() {
        return timeFactors.isEmpty() || timeFactors.contains("T_UNKNOWN");
    }

    private static String stripBrackets(String token) {
        String t = token;
        if (t.startsWith("<")) {
            t = t.substring(1);
        }
        if (t.endsWith(">")) {
            t = t.substring(0, t.length() - 1);
        }
        return t.toUpperCase(Locale.ROOT);
    }
}
