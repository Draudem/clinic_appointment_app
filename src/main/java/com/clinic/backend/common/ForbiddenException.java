package com.clinic.backend.common;

/** Операция запрещена текущему пользователю -> HTTP 403. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
