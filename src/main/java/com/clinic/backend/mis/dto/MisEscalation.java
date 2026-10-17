package com.clinic.backend.mis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MisEscalation(
        Long id,
        @JsonProperty("client_id") Long clientId,
        String description,
        @JsonProperty("created_at") String createdAt
) {}