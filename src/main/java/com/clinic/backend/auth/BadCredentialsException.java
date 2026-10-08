package com.clinic.backend.auth;

/** Неверный логин или пароль. HTTP-статус: 401. */
public class BadCredentialsException extends RuntimeException {

    public BadCredentialsException() {
        super("Неверный логин или пароль");
    }
}
