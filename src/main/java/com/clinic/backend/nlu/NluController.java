package com.clinic.backend.nlu;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Диагностический и интеграционный эндпоинт NLU-модели:
 *   POST /api/nlu/parse  {"text":"запиши к дантисту завтра утром"} -> frame
 *   GET  /api/nlu/status -> какая модель загружена, готова ли
 *
 * Требует JWT (как и все данные). Удобно проверять через Swagger UI.
 */
@RestController
@RequestMapping("/api/nlu")
@Tag(name = "NLU", description = "Диагностика локальной NLU-модели (ONNX)")
public class NluController {

    private final NluService nlu;

    public NluController(NluService nlu) {
        this.nlu = nlu;
    }

    @PostMapping("/parse")
    public NluParseResponse parse(@Valid @RequestBody NluParseRequest request) {
        NluFrame frame = nlu.parse(request.text());
        return NluParseResponse.from(request.text(), frame, nlu.status().get("model").toString());
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return nlu.status();
    }
}
