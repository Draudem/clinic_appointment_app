package com.clinic.backend.mis;

/** Конфликт в МИС (409). Например, попытка изменить отменённую запись. */
public class MisConflictException extends RuntimeException {
    public MisConflictException(String message) { super(message); }
}