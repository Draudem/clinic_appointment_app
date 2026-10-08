package com.clinic.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "логин обязателен")
        @Size(min = 3, max = 50, message = "логин: 3-50 символов")
        String login,

        @NotBlank(message = "пароль обязателен")
        @Size(min = 4, max = 100, message = "пароль: 4-100 символов")
        String password,

        /**
         * Роль можно указать явно (для создания администраторов),
         * по умолчанию — PATIENT.
         */
        String role) {
}
