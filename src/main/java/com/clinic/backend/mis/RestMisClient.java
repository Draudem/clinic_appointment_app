package com.clinic.backend.mis;

import com.clinic.backend.mis.dto.MisClientDto;
import com.clinic.backend.mis.dto.MisError;
import com.clinic.backend.mis.dto.MisEscalation;
import com.clinic.backend.mis.dto.MisVisit;
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
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    // Простой rate-limiter (скользящее окно)
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());

    public RestMisClient(MisProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
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
        HttpResponse<String> res = send("POST", "/clients", json);
        return parse(res.body(), MisClientDto.class);
    }

    @Override
    public MisVisit createVisit(Long clientId, Long doctorId, String scheduledAt) {
        String json = String.format(
                "{\"client_id\":%d,\"doctor_id\":%d,\"scheduled_at\":\"%s\"}",
                clientId, doctorId, scheduledAt
        );
        HttpResponse<String> res = send("POST", "/visits", json);
        return parse(res.body(), MisVisit.class);
    }

    @Override
    public MisVisit cancelVisit(Long visitId) {
        String json = "{\"status\":\"Отменена\"}";
        HttpResponse<String> res = send("PATCH", "/visits/" + visitId, json);
        return parse(res.body(), MisVisit.class);
    }

    @Override
    public MisEscalation createEscalation(Long clientId, String description) {
        // Экранируем кавычки в описании, чтобы не сломать JSON
        String safeDesc = description.replace("\"", "\\\"");
        String json = String.format("{\"client_id\":%d,\"description\":\"%s\"}", clientId, safeDesc);
        HttpResponse<String> res = send("POST", "/escalations", json);
        return parse(res.body(), MisEscalation.class);
    }

    // --- Инфраструктура HTTP ---

    private HttpResponse<String> send(String method, String path, String body) {
        rateLimit();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(props.getBaseUrl() + path))
                    .header("Authorization", "Bearer " + props.getToken())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()));

            if ("POST".equals(method)) b.POST(HttpRequest.BodyPublishers.ofString(body));
            else if ("PATCH".equals(method)) b.method("PATCH", HttpRequest.BodyPublishers.ofString(body));
            else b.GET();

            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            handleErrors(res);
            return res;
        } catch (Exception e) {
            throw new MisUnavailableException("Ошибка сети при запросе к МИС: " + e.getMessage(), e);
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
        if (code == 429) throw new MisUnavailableException("МИС: превышен лимит запросов. " + msg);
        throw new MisUnavailableException(msg);
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
}