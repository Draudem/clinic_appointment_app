package com.clinic.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "логин обязателен")
        String login,

        @NotBlank(message = "пароль обязателен")
        String password) {
}
