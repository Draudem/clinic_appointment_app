package com.clinic.backend.common;

/** Ресурс не найден -> HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
