package com.clinic.backend.mis;

/** Стенд МИС недоступен (сеть, нет токена, 5xx). */
public class MisUnavailableException extends RuntimeException {
    public MisUnavailableException(String message) { super(message); }
    public MisUnavailableException(String message, Throwable cause) { super(message, cause); }
}