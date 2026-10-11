package com.clinic.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Настройка OpenAPI/Swagger UI.
 *
 * Добавляет схему безопасности HTTP Bearer (JWT) и делает её требованием по
 * умолчанию, чтобы в Swagger UI появилась кнопка «Authorize» и защищённые
 * эндпоинты (/api/voice/**, /api/appointments, /api/users, ...) можно было
 * вызывать прямо из браузера.
 *
 * Порядок проверки:
 *   1. POST /api/auth/login  {"login":"admin","password":"admin123"} -> token
 *   2. Нажать «Authorize» и вставить токен (без слова "Bearer" — Swagger добавит сам)
 *   3. Вызывать защищённые эндпоинты через «Try it out»
 *
 * Публичные эндпоинты (/api/auth/login, /api/health, /v3/api-docs) требование
 * игнорируют — токен для них не нужен.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearer-jwt";

    @Bean
    public OpenAPI clinicOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Clinic Voice Backend")
                        .version("0.1.0")
                        .description("Бэкенд записи на приём: расписание, специальности, записи и "
                                + "голосовой ассистент (NLU-модель + RAG). "
                                + "Защищённые эндпоинты: получите токен в POST /api/auth/login, "
                                + "затем «Authorize» -> Bearer <токен>."))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT из POST /api/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
