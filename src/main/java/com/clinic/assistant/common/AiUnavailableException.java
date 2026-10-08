package com.clinic.assistant.common;

/**
 * 502 — внешняя ИИ-модель недоступна или ответила ошибкой.
 * Конвейер не падает целиком: клиент получает понятное сообщение.
 */
public class AiUnavailableException extends RuntimeException {
    public AiUnavailableException(String message) {
        super(message);
    }

    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
