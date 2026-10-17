package com.clinic.backend.mis;

/** Неверные данные (400). Например, врач деактивирован. */
public class MisBadRequestException extends RuntimeException {
    public MisBadRequestException(String message) { super(message); }
}