package com.clinic.backend.mis;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/mis")
@Tag(name = "MIS", description = "Диагностика интеграции с МИС")
public class MisController {

    private final MisClient misClient;

    public MisController(MisClient misClient) {
        this.misClient = misClient;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "available", misClient.available(),
                "client", misClient.getClass().getSimpleName()
        );
    }
}