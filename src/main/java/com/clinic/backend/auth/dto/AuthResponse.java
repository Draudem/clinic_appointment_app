package com.clinic.backend.auth.dto;

/**
 * Ответ логина/регистрации.
 * login и role — формат, который ждёт и фронтенд (API.login), и мобильное приложение;
 * token присутствует только в ответе на login.
 */
public record AuthResponse(String token, String login, String role) {

    public static AuthResponse user(String login, String role) {
        return new AuthResponse(null, login, role);
    }
}
