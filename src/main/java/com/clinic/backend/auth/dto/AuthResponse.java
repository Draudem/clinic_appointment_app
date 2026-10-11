package com.clinic.backend.auth.dto;

/**
 * Ответ логина/регистрации.
 * login и role — формат, который ждёт и фронтенд (API.login), и мобильное приложение;
 * token присутствует только в ответе на login.
 * firstName/lastName добавлены как обязательные поля пользователя (идут дополнительно,
 * старые потребители, читающие только login/role/token, продолжают работать).
 */
public record AuthResponse(String token, String login, String role, String firstName, String lastName) {

    public static AuthResponse user(String login, String role, String firstName, String lastName) {
        return new AuthResponse(null, login, role, firstName, lastName);
    }
}
