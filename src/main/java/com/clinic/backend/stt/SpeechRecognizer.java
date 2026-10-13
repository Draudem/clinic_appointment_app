package com.clinic.backend.stt;

/**
 * Порт распознавания речи: 16 кГц PCM -> текст.
 *
 * Реализация — {@link SherpaSttEngine} (GigaAM). Порт нужен для подмены движка
 * (другой ASR, облачный сервис) и для заглушки в тестах, не трогая ни
 * AudioSocket-сервер, ни диалог.
 */
public interface SpeechRecognizer {

    /**
     * Распознать одну фразу.
     *
     * @param samples16k PCM float32, 16 кГц, моно, [-1..1]
     * @return распознанный текст; пустая строка, если речь неразборчива
     */
    String transcribe(float[] samples16k);

    /** Готова ли модель к работе. */
    boolean ready();

    /** Причина недоступности (для /api/stt/status). */
    String error();

    /** Имя движка для диагностики. */
    String name();
}