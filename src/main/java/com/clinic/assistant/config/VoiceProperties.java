package com.clinic.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Настройки голосового конвейера и подбора записи.
 */
@ConfigurationProperties(prefix = "app.voice")
public class VoiceProperties {

    /** Куда сохранять загруженные аудиофайлы. */
    private String storageDir = "./data/voice";

    /** На сколько дней вперёд искать свободные слоты, если время в запросе не указано. */
    private int searchDaysAhead = 14;

    /** Максимум вариантов записи, которые возвращаются клиенту. */
    private int maxCandidates = 5;

    /**
     * Сопоставление токенов модели специальностей с названиями специальностей в БД.
     * Токены — из модели парсера (см. models/tokenizer.json).
     */
    private Map<String, String> specialtyMapping = defaultSpecialtyMapping();

    public static Map<String, String> defaultSpecialtyMapping() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("GP", "Терапевт");
        m.put("DENTIST", "Стоматолог");
        m.put("DERMATOLOGIST", "Дерматолог");
        m.put("GYNECOLOGIST", "Гинеколог");
        m.put("OPHTHALMOLOGIST", "Офтальмолог");
        m.put("NEUROLOGIST", "Невролог");
        m.put("PSYCHOLOGIST", "Психолог");
        m.put("ENT", "ЛОР");
        return m;
    }

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }

    public int getSearchDaysAhead() {
        return searchDaysAhead;
    }

    public void setSearchDaysAhead(int searchDaysAhead) {
        this.searchDaysAhead = searchDaysAhead;
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public void setMaxCandidates(int maxCandidates) {
        this.maxCandidates = maxCandidates;
    }

    public Map<String, String> getSpecialtyMapping() {
        return specialtyMapping;
    }

    public void setSpecialtyMapping(Map<String, String> specialtyMapping) {
        this.specialtyMapping = specialtyMapping;
    }
}
