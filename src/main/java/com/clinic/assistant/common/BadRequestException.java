package com.clinic.assistant.common;

/** 400 — некорректный запрос. */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
