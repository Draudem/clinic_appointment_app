package com.clinic.backend.mis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки интеграции с тестовым стендом МИС.
 * Читаются из application.yml (app.mis.*).
 */
@ConfigurationProperties(prefix = "app.mis")
public class MisProperties {

    /** Включать ли интеграцию. Если false или нет токена — работает заглушка. */
    private boolean enabled = true;

    /** Базовый URL стенда. */
    private String baseUrl = "https://crm-med-mock.dbi-testing.ru/api/v1";

    /** Bearer-токен команды. */
    private String token = "";

    /** Таймаут HTTP-запроса в секундах. */
    private int timeoutSeconds = 10;

    /** Локальный лимит запросов (стенд позволяет 10/мин, ставим 8 для запаса). */
    private int rateLimitPerMinute = 8;

    // --- Геттеры и сеттеры ---
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getRateLimitPerMinute() { return rateLimitPerMinute; }
    public void setRateLimitPerMinute(int rateLimitPerMinute) { this.rateLimitPerMinute = rateLimitPerMinute; }
}