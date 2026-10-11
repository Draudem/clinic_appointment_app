package com.clinic.backend.voice;

/** STT недоступен (выключен или движок не подключён). */
public class SpeechToTextException extends RuntimeException {

    public SpeechToTextException(String message) {
        super(message);
    }
}
