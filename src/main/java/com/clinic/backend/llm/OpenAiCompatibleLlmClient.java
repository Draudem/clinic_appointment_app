package com.clinic.backend.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Клиент OpenAI-совместимого chat/completions.
 * Работает с Ollama (base-url http://host:11434/v1, api-key не нужен) и с
 * любым облачным провайдером. Реализован на JDK HttpClient — без лишних
 * зависимостей, чтобы образ оставался маленьким.
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final LlmProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public OpenAiCompatibleLlmClient(LlmProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public boolean enabled() {
        return props.isEnabled();
    }

    @Override
    public String modelName() {
        return props.getModel();
    }

    @Override
    public String chat(String system, String user) {
        if (!props.isEnabled()) {
            throw new LlmUnavailableException("LLM выключена (app.llm.enabled=false)");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.getModel());
        body.put("stream", false);
        body.put("temperature", 0.1);
        ArrayNode messages = body.putArray("messages");
        messages.add(message("system", system));
        messages.add(message("user", user));

        String url = props.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (props.getApiKey() != null && !props.getApiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + props.getApiKey());
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new LlmUnavailableException("LLM вернула HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode()) {
                throw new LlmUnavailableException("Неожиданный ответ LLM: " + response.body());
            }
            return content.asText();
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmUnavailableException("Не удалось обратиться к LLM (" + url + "): " + e.getMessage(), e);
        }
    }

    private ObjectNode message(String role, String content) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }

    /** Полезно для диагностики: список настроек без секретов. */
    public Map<String, Object> describe() {
        return Map.of(
                "enabled", props.isEnabled(),
                "baseUrl", props.getBaseUrl(),
                "model", props.getModel(),
                "timeoutSeconds", props.getTimeoutSeconds());
    }

    /** Список сообщений — зарезервировано под многошаговый диалог. */
    public String chat(List<Map<String, String>> messages) {
        StringBuilder user = new StringBuilder();
        for (Map<String, String> m : messages) {
            user.append(m.get("role")).append(": ").append(m.get("content")).append('\n');
        }
        return chat("Ты — ассистент клиники.", user.toString());
    }
}
