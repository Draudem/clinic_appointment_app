package com.clinic.backend.common;

/** Конфликт состояния (занятый слот, врач не работает в этот день и т.п.) -> HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
