package com.clinic.backend.mis;

import com.clinic.backend.mis.dto.MisClientDto;
import com.clinic.backend.mis.dto.MisError;
import com.clinic.backend.mis.dto.MisEscalation;
import com.clinic.backend.mis.dto.MisVisit;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Реальная интеграция со стендом «Регистратура» через HTTP.
 */
public class RestMisClient implements MisClient {

    private static final Logger log = LoggerFactory.getLogger(RestMisClient.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final MisProperties props;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final HttpClient http;
    private static final int MIS_PAGE_LIMIT = 50;

    // Простой rate-limiter (скользящее окно)
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());

    public RestMisClient(MisProperties props) {
        this.props = props;

        System.setProperty("java.net.preferIPv4Stack", "true");

        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        log.info("Интеграция с МИС включена: {}", props.getBaseUrl());
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public MisClientDto findOrCreateClient(String lastName, String firstName, LocalDate birthDate) {
        String json = String.format(
                "{\"last_name\":\"%s\",\"first_name\":\"%s\",\"birth_date\":\"%s\"}",
                lastName, firstName, birthDate.format(DATE_FMT)
        );
        log.info("МИС: POST /clients, пациент {}", mask(lastName, firstName));
        HttpResponse<String> res = send("POST", "/clients", json);
        return parse(res.body(), MisClientDto.class);
    }

    @Override
    public MisVisit createVisit(Long clientId, Long doctorId, String scheduledAt) {
        String json = String.format(
                "{\"client_id\":%d,\"doctor_id\":%d,\"scheduled_at\":\"%s\"}",
                clientId, doctorId, scheduledAt
        );
        log.info("МИС: POST /visits, client_id={}, doctor_id={}, scheduled_at={}",
                clientId, doctorId, scheduledAt);
        HttpResponse<String> res = send("POST", "/visits", json);
        return parse(res.body(), MisVisit.class);
    }

    @Override
    public MisVisit cancelVisit(Long visitId) {
        String json = "{\"status\":\"Отменена\"}";
        log.info("МИС: PATCH /visits/{}, отмена", visitId);
        HttpResponse<String> res = send("PATCH", "/visits/" + visitId, json);
        return parse(res.body(), MisVisit.class);
    }

    @Override
    public MisEscalation createEscalation(Long clientId, String description) {
        String safeDesc = description.replace("\"", "\\\"");
        String json = String.format("{\"client_id\":%d,\"description\":\"%s\"}", clientId, safeDesc);
        log.info("МИС: POST /escalations, client_id={}, описание ({} симв.)",
                clientId, description == null ? 0 : description.length());
        HttpResponse<String> res = send("POST", "/escalations", json);
        return parse(res.body(), MisEscalation.class);
    }

    /**
     * Ищет ID врача на стенде по его ФИО.
     * Стенд и наш Excel имеют разные нумерации, поэтому искать нужно по имени.
     */
    public Long findDoctorIdByFio(String fio) {
        if (fio == null || fio.isBlank()) return null;
        try {
            HttpResponse<String> res = send("GET", "/doctors?limit=" + MIS_PAGE_LIMIT, null);            String body = res.body();

            int fioIndex = body.indexOf("\"" + fio + "\"");
            if (fioIndex == -1) {
                log.warn("МИС: врач с ФИО '{}' не найден на стенде!", fio);
                return null;
            }

            int start = Math.max(0, fioIndex - 100);
            String prefix = body.substring(start, fioIndex);
            int idIndex = prefix.lastIndexOf("\"id\":");
            if (idIndex != -1) {
                String idStr = prefix.substring(idIndex + 5).replaceAll("[^0-9].*", "");
                if (!idStr.isEmpty()) {
                    return Long.parseLong(idStr);
                }
            }
        } catch (Exception e) {
            log.error("МИС: ошибка поиска ID врача по ФИО '{}': {}", fio, e.getMessage());
        }
        return null;
    }

    /**
     * Получает список активных записей пациента из МИС.
     */
    public String getActiveVisits(Long clientId) {
        log.info("МИС: GET /visits?client_id={}&status=Запланирована", clientId);
        HttpResponse<String> res = send("GET", "/visits?client_id=" + clientId + "&status=Запланирована", null);
        return res.body();
    }

    /**
     * Пример запроса списка с пагинацией (лимит 50 — требование стенда).
     */
    public String fetchDoctorsPage(int offset) {
        log.info("МИС: GET /doctors?limit=50&offset={}", offset);
        HttpResponse<String> res = send("GET", "/doctors?limit=50&offset=" + offset, null);
        return res.body();
    }

    // --- Инфраструктура HTTP ---

    private HttpResponse<String> send(String method, String path, String body) {
        rateLimit();
        String url = props.getBaseUrl() + path;
        log.debug("МИС: {} {}", method, url);

        int attempt = 0;
        int maxAttempts = Math.max(1, props.getRetryOn429() + 1);
        while (true) {
            attempt++;
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Bearer " + props.getToken())
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "ClinicVoiceAssistant/1.0")
                        .timeout(Duration.ofSeconds(props.getTimeoutSeconds()));

                if ("POST".equals(method) || "PATCH".equals(method)) {
                    String safeBody = body == null ? "{}" : body;
                    b.method(method, HttpRequest.BodyPublishers.ofString(safeBody));
                } else {
                    b.GET();
                }

                HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                log.debug("МИС: ответ HTTP {} на {} {} (попытка {}/{})",
                        res.statusCode(), method, path, attempt, maxAttempts);

                if (res.statusCode() == 429 && attempt < maxAttempts) {
                    long wait = parseRetryAfter(res);
                    long capped = Math.min(wait, props.getRetryMaxWaitSeconds());
                    log.warn("МИС: 429 на {} {} — ждём {} с (Retry-After={}, попытка {}/{}), повторяем",
                            method, path, capped, wait, attempt, maxAttempts);
                    try {
                        Thread.sleep(capped * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new MisUnavailableException("Прервано ожидание повтора после 429");
                    }
                    continue;
                }

                handleErrors(res);
                return res;
            } catch (java.net.http.HttpConnectTimeoutException e) {
                throw new MisUnavailableException("Таймаут подключения к МИС (нет доступа к "
                        + props.getBaseUrl() + ")", e);
            } catch (MisRateLimitedException | MisUnavailableException e) {
                throw e;
            } catch (Exception e) {
                throw new MisUnavailableException("Ошибка сети при запросе к МИС: "
                        + e.getMessage(), e);
            }
        }
    }

    private void handleErrors(HttpResponse<String> res) {
        int code = res.statusCode();
        if (code >= 200 && code < 300) return;

        String msg = "Ошибка МИС (HTTP " + code + ")";
        try {
            MisError err = mapper.readValue(res.body(), MisError.class);
            msg = err.message();
        } catch (Exception ignored) {}

        if (code == 400) throw new MisBadRequestException(msg);
        if (code == 409) throw new MisConflictException(msg);
        if (code == 429) {
            long wait = parseRetryAfter(res);
            throw new MisRateLimitedException(
                    "МИС: превышен лимит запросов (429). Retry-After=" + wait + " с. " + msg, wait);
        }
        throw new MisUnavailableException(msg);
    }

    static long parseRetryAfter(HttpResponse<String> res) {
        String value = res.headers().firstValue("Retry-After").orElse(null);
        if (value == null || value.isBlank()) {
            return MisRateLimitedException.DEFAULT_RETRY_AFTER_SECONDS;
        }
        String v = value.trim();
        if (v.chars().allMatch(Character::isDigit)) {
            try {
                return Long.parseLong(v);
            } catch (NumberFormatException e) {
                return MisRateLimitedException.DEFAULT_RETRY_AFTER_SECONDS;
            }
        }
        try {
            var fmt = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
            var at = java.time.ZonedDateTime.parse(v, fmt);
            long seconds = java.time.Duration.between(java.time.ZonedDateTime.now(), at).getSeconds();
            return Math.max(1L, seconds);
        } catch (Exception e) {
            return MisRateLimitedException.DEFAULT_RETRY_AFTER_SECONDS;
        }
    }

    private <T> T parse(String json, Class<T> clazz) {
        try {
            return mapper.readValue(json, clazz);
        } catch (Exception e) {
            throw new MisUnavailableException("Не удалось разобрать ответ МИС: " + json, e);
        }
    }

    private void rateLimit() {
        long now = System.currentTimeMillis();
        long start = windowStart.get();
        if (now - start > 60_000) {
            windowStart.set(now);
            requestCount.set(0);
        }
        if (requestCount.incrementAndGet() > props.getRateLimitPerMinute()) {
            throw new MisUnavailableException("Локальный лимит запросов к МИС исчерпан.");
        }
    }

    static String mask(String last, String first) {
        StringBuilder sb = new StringBuilder();
        if (last != null && !last.isBlank()) sb.append(last.trim());
        if (first != null && !first.isBlank()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(first.charAt(0)).append('.');
        }
        return sb.length() == 0 ? "—" : sb.toString();
    }
}