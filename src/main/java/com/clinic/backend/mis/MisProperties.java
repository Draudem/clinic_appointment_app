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

    /**
     * Сколько раз повторять запрос при 429 (Retry-After).
     * По умолчанию 2: стенд даёт 10/мин, локальный лимит 8, так что штатно
     * до 429 доходить не должно, но при всплеске (несколько звонков сразу)
     * повтор дешевле, чем перевод оператору.
     */
    private int retryOn429 = 2;

    /**
     * Верхняя граница ожидания повтора, секунды. Если стенд просит больше
     * (например, 60), не ждём — переводим оператору: пациент не должен
     * висеть на линии минуту.
     */
    private int retryMaxWaitSeconds = 5;

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
    public int getRetryOn429() { return retryOn429; }
    public void setRetryOn429(int retryOn429) { this.retryOn429 = retryOn429; }
    public int getRetryMaxWaitSeconds() { return retryMaxWaitSeconds; }
    public void setRetryMaxWaitSeconds(int retryMaxWaitSeconds) { this.retryMaxWaitSeconds = retryMaxWaitSeconds; }
}