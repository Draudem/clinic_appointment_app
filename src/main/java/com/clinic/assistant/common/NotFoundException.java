package com.clinic.assistant.common;

/** 404 — ресурс не найден. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
