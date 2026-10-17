package com.clinic.backend.mis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MisClientDto(
        Long id,
        @JsonProperty("last_name") String lastName,
        @JsonProperty("first_name") String firstName,
        @JsonProperty("birth_date") String birthDate
) {}