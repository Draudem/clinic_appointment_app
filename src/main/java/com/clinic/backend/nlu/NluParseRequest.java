package com.clinic.backend.nlu;

import jakarta.validation.constraints.NotBlank;

/** Запрос на разбор текста: POST /api/nlu/parse {"text": "..."} */
public record NluParseRequest(
        @NotBlank(message = "text обязателен")
        String text) {
}
