package com.clinic.backend.common;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Проверка живости приложения (для Docker healthcheck и отладки). */
@RestController
@Tag(name = "Health", description = "Проверка живости приложения")
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
