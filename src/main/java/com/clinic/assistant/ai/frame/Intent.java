package com.clinic.assistant.ai.frame;

/**
 * Интент из фрейма модели.
 */
public enum Intent {
    /** Записаться на приём. */
    BOOK,
    /** Перенести запись. */
    RESCHEDULE,
    /** Отменить запись. */
    CANCEL,
    /** Модель не поняла запрос. */
    UNSUPPORTED
}
