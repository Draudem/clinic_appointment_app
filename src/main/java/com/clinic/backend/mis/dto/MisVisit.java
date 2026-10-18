package com.clinic.backend.mis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Ответ стенда: карточка записи на приём. */
public record MisVisit(
        Long id,
        @JsonProperty("client_id") Long clientId,
        MisDoctor doctor,
        @JsonProperty("scheduled_at") String scheduledAt,
        String status,
        @JsonProperty("created_at") String createdAt
) {
    public record MisDoctor(Long id, @JsonProperty("full_name") String fullName,
                            String specialty, @JsonProperty("is_active") Boolean isActive) {}
}