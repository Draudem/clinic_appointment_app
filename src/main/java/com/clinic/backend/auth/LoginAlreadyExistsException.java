package com.clinic.backend.auth;

/** Логин уже занят при регистрации. HTTP-статус: 409. */
public class LoginAlreadyExistsException extends RuntimeException {

    public LoginAlreadyExistsException(String login) {
        super("Логин '" + login + "' уже занят");
    }
}
