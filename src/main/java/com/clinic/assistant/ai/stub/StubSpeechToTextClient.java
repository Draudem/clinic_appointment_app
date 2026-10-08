package com.clinic.assistant.ai.stub;

import com.clinic.assistant.ai.SpeechToTextClient;

/**
 * ЗАГЛУШКА этапа STT: возвращает заранее заданный текст вместо распознавания аудио.
 *
 * <p>Позволяет разрабатывать и демонстрировать весь конвейер без установленной
 * модели. Текст настраивается свойством {@code app.ai.stt.stub-text}.</p>
 */
public class StubSpeechToTextClient implements SpeechToTextClient {

    private static final String DEFAULT_TEXT = "записаться к терапевту завтра в десять утра";

    private final String text;

    public StubSpeechToTextClient(String configuredText) {
        this.text = (configuredText == null || configuredText.isBlank()) ? DEFAULT_TEXT : configuredText;
    }

    @Override
    public SpeechToTextResult transcribe(AudioContent audio) {
        return new SpeechToTextResult(text, "stub");
    }
}
