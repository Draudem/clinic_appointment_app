package com.clinic.assistant.ai;

/**
 * ПОРТ (интерфейс) этапа 1 конвейера: распознавание речи.
 *
 * <p>Реализации подключаются в {@code AiClientsConfig} по свойству
 * {@code app.ai.stt.provider}:</p>
 * <ul>
 *   <li>{@code StubSpeechToTextClient} — заглушка (ничего внешнего не нужно);</li>
 *   <li>{@code HttpSpeechToTextClient} — запрос к развёрнутой на бэке модели коллеги.</li>
 * </ul>
 *
 * <p>Чтобы поставить новый, более продвинутый движок — достаточно написать класс,
 * реализующий этот интерфейс, и зарегистрировать бин. Ничто другое в проекте не меняется.</p>
 */
public interface SpeechToTextClient {

    /** Аудио, полученное от мобильного приложения. */
    SpeechToTextResult transcribe(AudioContent audio);

    /** Содержимое аудиофайла + метаданные. */
    record AudioContent(byte[] data, String contentType, String filename) {

        public static AudioContent of(byte[] data, String contentType, String filename) {
            String name = (filename == null || filename.isBlank()) ? "voice" : filename;
            String type = (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType;
            return new AudioContent(data, type, name);
        }
    }

    /** Результат распознавания: текст + имя/версия модели (для журнала). */
    record SpeechToTextResult(String text, String model) {
    }
}
