package com.clinic.backend.llm;

import com.clinic.backend.common.ConflictException;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Диагностика LLM-этапа:
 *   GET  /api/llm/status -> включена ли, какая модель, куда ходит
 *   POST /api/llm/echo {"prompt":"..."} -> пробный запрос к модели
 *
 * Полезно для проверки Ollama перед включением RAG-парсера:
 *   LLM_ENABLED=true LLM_BASE_URL=http://<host>:11434/v1 LLM_MODEL=qwen2.5:1.5b-instruct
 */
@RestController
@RequestMapping("/api/llm")
@Tag(name = "LLM", description = "Проверка внешней LLM (Ollama), опционально")
public class LlmController {

    private final LlmClient client;

    public LlmController(LlmClient client) {
        this.client = client;
    }

    public record EchoRequest(String prompt) {
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        if (client instanceof OpenAiCompatibleLlmClient openAi) {
            return openAi.describe();
        }
        return Map.of("enabled", client.enabled(), "model", client.modelName());
    }

    @PostMapping("/echo")
    public Map<String, Object> echo(@RequestBody(required = false) EchoRequest request) {
        String prompt = request == null || request.prompt() == null || request.prompt().isBlank()
                ? "Ответь одним словом: работает?"
                : request.prompt();
        try {
            String answer = client.chat(
                    "Ты — ассистент клиники. Отвечай коротко и по-русски.",
                    prompt);
            return Map.of("model", client.modelName(), "answer", answer);
        } catch (LlmUnavailableException e) {
            throw new ConflictException(e.getMessage());
        }
    }
}
