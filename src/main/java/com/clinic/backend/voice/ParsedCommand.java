package com.clinic.backend.voice;

import java.util.List;

/**
 * Результат парсинга пользовательской фразы в команду (без привязки к БД).
 * Разрешение в конкретного врача/дату делает SlotResolver.
 */
public record ParsedCommand(
        String intent,             // BOOK | RESCHEDULE | CANCEL | UNSUPPORTED | null
        String specialty,          // русское название специальности или null
        List<String> timeFactors,  // сырые факторы времени от модели
        List<String> symptoms,     // <SYMPTOM_*> или названия симптомов
        boolean timeUnknown,
        boolean needsClarification,
        boolean unsupported,
        String frame,              // каноническая строка токенов (для логов/отладки)
        String engine) {           // какой парсер сработал: nlu/rule/rag-llm

    public boolean hasSpecialty() {
        return specialty != null && !specialty.isBlank();
    }
}
