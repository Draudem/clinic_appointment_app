package com.clinic.assistant.voice;

import com.clinic.assistant.ai.frame.VoiceFrame;
import com.clinic.assistant.clinic.Specialty;
import com.clinic.assistant.clinic.SpecialtyRepository;
import com.clinic.assistant.config.VoiceProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Определяет специальность из фрейма модели.
 *
 * <ol>
 *   <li>токен модели ({@code NEUROLOGIST}) → название из конфига → специальность в БД;</li>
 *   <li>если модель вернула {@code UNKNOWN_SPECIALTY} — ищем название специальности
 *       (и разговорные синонимы) прямо в распознанном тексте.</li>
 * </ol>
 */
@Component
public class SpecialtyResolver {

    /** Разговорные синонимы → название специальности в БД. */
    private static final Map<String, String> SYNONYMS = new LinkedHashMap<>();

    static {
        SYNONYMS.put("окулист", "Офтальмолог");
        SYNONYMS.put("зрени", "Офтальмолог");
        SYNONYMS.put("невропатолог", "Невролог");
        SYNONYMS.put("кардиолог", "Кардиолог");
        SYNONYMS.put("сердц", "Кардиолог");
        SYNONYMS.put("женск", "Гинеколог");
        SYNONYMS.put("детск", "Педиатр");
        SYNONYMS.put("ребен", "Педиатр");
        SYNONYMS.put("кожн", "Дерматолог");
        SYNONYMS.put("эндокринолог", "Эндокринолог");
        SYNONYMS.put("щитовидн", "Эндокринолог");
        SYNONYMS.put("хирург", "Хирург");
        SYNONYMS.put("уролог", "Уролог");
        SYNONYMS.put("цистит", "Уролог");
        SYNONYMS.put("ортопед", "Ортопед");
        SYNONYMS.put("сустав", "Ортопед");
        SYNONYMS.put("позвоночник", "Ортопед");
        SYNONYMS.put("лор", "ЛОР");
        SYNONYMS.put("уши", "ЛОР");
        SYNONYMS.put("насморк", "ЛОР");
        SYNONYMS.put("стоматолог", "Стоматолог");
        SYNONYMS.put("зуб", "Стоматолог");
        SYNONYMS.put("психолог", "Психолог");
        SYNONYMS.put("тревог", "Психолог");
        SYNONYMS.put("терапевт", "Терапевт");
        SYNONYMS.put("врач общ", "Терапевт");
        SYNONYMS.put("семейн", "Терапевт");
        SYNONYMS.put("гинеколог", "Гинеколог");
        SYNONYMS.put("офтальмолог", "Офтальмолог");
        SYNONYMS.put("невролог", "Невролог");
        SYNONYMS.put("дерматолог", "Дерматолог");
    }

    private final SpecialtyRepository specialtyRepository;
    private final VoiceProperties properties;

    public SpecialtyResolver(SpecialtyRepository specialtyRepository, VoiceProperties properties) {
        this.specialtyRepository = specialtyRepository;
        this.properties = properties;
    }

    /** @return специальность или null, если определить не удалось. */
    public Specialty resolve(VoiceFrame frame, String transcript) {
        // 1) токен модели → название → БД
        String token = frame.specialty();
        if (token != null && !"UNKNOWN_SPECIALTY".equals(token)) {
            String name = properties.getSpecialtyMapping().get(token);
            if (name != null) {
                var found = specialtyRepository.findByNameIgnoreCase(name);
                if (found.isPresent()) {
                    return found.get();
                }
            }
        }

        // 2) разбор исходного текста
        String text = normalize(transcript);
        if (text.isEmpty()) {
            return null;
        }
        for (Specialty specialty : specialtyRepository.findAll()) {
            if (text.contains(specialty.getName().toLowerCase(Locale.ROOT))) {
                return specialty;
            }
        }
        for (Map.Entry<String, String> e : SYNONYMS.entrySet()) {
            if (text.contains(e.getKey())) {
                var found = specialtyRepository.findByNameIgnoreCase(e.getValue());
                if (found.isPresent()) {
                    return found.get();
                }
            }
        }
        return null;
    }

    private static String normalize(String text) {
        return (text == null ? "" : text).toLowerCase(Locale.ROOT).replace('ё', 'е').trim();
    }
}
