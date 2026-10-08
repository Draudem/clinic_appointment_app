package com.clinic.assistant.common;

/** 401 — неверный логин/пароль или нет авторизации. */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
