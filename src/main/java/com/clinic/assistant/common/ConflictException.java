package com.clinic.assistant.common;

/** 409 — конфликт (слот уже занят, логин занят и т.п.). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
