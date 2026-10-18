package com.clinic.backend.mis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Ответ стенда на POST /clients (и на GET /clients/{id}).
 *
 * <p>Стенд возвращает больше полей, чем мы используем: {@code middle_name},
 * {@code comment}, {@code created_at}. Если их не объявить — Jackson с
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=true} падает, и сценарий уходит в оператора.
 * Здесь поля объявлены явно, чтобы ответ разбирался без настройки ObjectMapper.
 * Альтернатива (и она тоже сделана) — {@code FAIL_ON_UNKNOWN_PROPERTIES=false}
 * в {@code RestMisClient}.
 */
public record MisClientDto(
        Long id,
        @JsonProperty("last_name") String lastName,
        @JsonProperty("first_name") String firstName,
        @JsonProperty("middle_name") String middleName,
        @JsonProperty("birth_date") String birthDate,
        String comment,
        @JsonProperty("created_at") String createdAt
) {}