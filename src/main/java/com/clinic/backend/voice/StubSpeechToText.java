package com.clinic.backend.voice;

import org.springframework.stereotype.Component;

/**
 * Заглушка STT. На устройстве распознавание делает мобильное приложение
 * (sherpa-onnx GigaAM), поэтому серверное аудио пока не поддерживается —
 * заглушка честно сообщает об этом, чтобы сценарий можно было заменить,
 * не трогая остальные слои.
 */
@Component
public class StubSpeechToText implements SpeechToText {

    @Override
    public String transcribe(byte[] audio, String contentType) {
        throw new SpeechToTextException(
                "Серверное распознавание речи пока не подключено (заглушка). "
                        + "Мобильное приложение распознаёт речь на устройстве и присылает "
                        + "текст в POST /api/voice/command.");
    }

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public boolean available() {
        return false;
    }
}
