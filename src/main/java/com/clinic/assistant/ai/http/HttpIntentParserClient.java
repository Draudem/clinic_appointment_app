package com.clinic.assistant.ai.http;

import com.clinic.assistant.ai.IntentParserClient;
import com.clinic.assistant.ai.frame.Intent;
import com.clinic.assistant.ai.frame.VoiceFrame;
import com.clinic.assistant.common.AiUnavailableException;
import com.clinic.assistant.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP-клиент этапа разбора текста: отправляет транскрипт в сервис,
 * внутри которого развёрнута ONNX-модель парсера (models/model.onnx).
 *
 * <h2>Контракт сервиса</h2>
 * <pre>
 * POST {app.ai.parser.url}
 * Content-Type: application/json
 * { "text": "записаться к неврологу завтра в 10:00" }
 *
 * 200 OK — оба варианта ответа принимаются:
 * (1) структурированный:
 * { "intent": "BOOK", "specialty": "NEUROLOGIST",
 *   "time": ["T_TOMORROW", "T_H10_00"], "symptoms": [],
 *   "needsClarification": false,
 *   "raw": "&lt;BOOK&gt; &lt;NEUROLOGIST&gt; &lt;T_TOMORROW&gt; &lt;T_H10_00&gt; &lt;END_FRAME&gt;" }
 *
 * (2) только «сырой» фрейм:
 * { "raw": "&lt;BOOK&gt; &lt;NEUROLOGIST&gt; &lt;T_TOMORROW&gt; &lt;T_H10_00&gt; &lt;END_FRAME&gt;" }
 * </pre>
 *
 * <p>Вариант (2) удобен тем, кто не хочет парсить сам — бэкенд сам разложит
 * строку токенов в структуру ({@link VoiceFrame#fromRaw}).</p>
 */
public class HttpIntentParserClient implements IntentParserClient {

    private final RestClient restClient;
    private final String url;

    public HttpIntentParserClient(AiProperties.Stage stage) {
        this.url = stage.getUrl();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(stage.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(stage.getReadTimeoutMs()));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public VoiceFrame parse(String text) {
        try {
            JsonNode response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", text == null ? "" : text))
                    .retrieve()
                    .body(JsonNode.class);
            return toFrame(response);
        } catch (RestClientResponseException e) {
            throw new AiUnavailableException(
                    "Парсер ответил ошибкой " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            throw new AiUnavailableException(
                    "Не удалось подключиться к парсеру (" + url + "): " + e.getMessage(), e);
        }
    }

    private VoiceFrame toFrame(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            throw new AiUnavailableException("Парсер вернул пустой ответ (url=" + url + ")");
        }

        // Вариант (1): структурированный ответ
        if (node.hasNonNull("intent")) {
            Intent intent = parseIntent(node.get("intent").asText());
            String specialty = node.hasNonNull("specialty") && !node.get("specialty").isNull()
                    ? node.get("specialty").asText()
                    : null;
            List<String> timeFactors = readStringList(node, "time", "timeFactors");
            List<String> symptoms = readStringList(node, "symptoms");
            boolean clarification = node.path("needsClarification").asBoolean(false);
            String raw = node.hasNonNull("raw")
                    ? node.get("raw").asText()
                    : VoiceFrame.buildRaw(intent, specialty, timeFactors, symptoms, clarification);
            return new VoiceFrame(intent, specialty, timeFactors, symptoms, clarification, raw);
        }

        // Вариант (2): только сырая строка фрейма
        if (node.hasNonNull("raw")) {
            return VoiceFrame.fromRaw(node.get("raw").asText());
        }

        throw new AiUnavailableException("Ответ парсера не содержит ни поля intent, ни поля raw: " + node);
    }

    private static Intent parseIntent(String value) {
        String v = value.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "BOOK", "BOOKING" -> Intent.BOOK;
            case "RESCHEDULE", "MOVE" -> Intent.RESCHEDULE;
            case "CANCEL" -> Intent.CANCEL;
            default -> Intent.UNSUPPORTED;
        };
    }

    /** Читает массив строк; поддерживает и строку-«одиночку». */
    private static List<String> readStringList(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) {
                continue;
            }
            List<String> result = new ArrayList<>();
            if (value.isArray()) {
                value.forEach(item -> {
                    String s = item.asText(null);
                    if (s != null && !s.isBlank()) {
                        result.add(s.trim());
                    }
                });
            } else if (value.isTextual() && !value.asText().isBlank()) {
                result.add(value.asText().trim());
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        return List.of();
    }
}
