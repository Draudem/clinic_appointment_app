package com.clinic.assistant.voice;

import com.clinic.assistant.ai.frame.VoiceFrame;

import java.util.List;

/**
 * Ответ конвейера на голосовой запрос — всё, что нужно мобильному приложению
 * для отрисовки результата и подтверждения записи.
 *
 * @param requestId            id запроса в журнале (voice_request)
 * @param text                 распознанный текст
 * @param frame                разобранный фрейм модели
 * @param resolved             во что фрейм превратился в терминах БД
 * @param candidates           варианты: для BOOK — свободные слоты,
 *                             для CANCEL/RESCHEDULE — свои записи
 * @param question             уточняющий вопрос пользователю (null — не нужен)
 * @param message              пояснение для отображения
 * @param availableSpecialties список специальностей (когда их нужно выбрать)
 */
public record VoiceCommandResponse(Long requestId,
                                   String text,
                                   VoiceFrame frame,
                                   Resolved resolved,
                                   List<Candidate> candidates,
                                   String question,
                                   String message,
                                   List<String> availableSpecialties) {

    public record Resolved(String specialty,
                           String dateFrom,
                           String dateTo,
                           String timeFrom,
                           String timeTo) {
    }

    public record Candidate(Long appointmentId,
                            Long doctorId,
                            String fio,
                            String specialty,
                            String date,
                            String time,
                            Long slotId,
                            boolean available) {
    }
}
