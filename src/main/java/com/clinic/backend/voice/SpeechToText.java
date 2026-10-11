package com.clinic.backend.voice;

/**
 * Портовый контракт распознавания речи (аудио -> текст).
 * В мобильном приложении STT выполняется НА УСТРОЙСТВЕ (sherpa-onnx GigaAM),
 * поэтому на бэкенд обычно приходит уже текст. Этот порт нужен для сценария,
 * когда аудио всё же отправляют на сервер: сейчас — заглушка, затем — движок
 * (например, тот же sherpa-onnx/whisper на отдельном сервере).
 */
public interface SpeechToText {

    /**
     * @param audio       сырое аудио (wav/ogg/...)
     * @param contentType MIME-тип
     * @return распознанный текст
     * @throws SpeechToTextException если STT недоступен
     */
    String transcribe(byte[] audio, String contentType);

    String name();

    boolean available();
}
