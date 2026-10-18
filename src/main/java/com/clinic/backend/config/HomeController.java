package com.clinic.backend.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Редирект с корня на Swagger UI.
 *
 * <p>Зачем: по условиям сдачи приложение должно быть доступно по
 * {@code http://<host>:8000/} — «по нему открывается демо». Отдавать на корне
 * JSON {@code {"status":"UP"}} или 404 неудобно: проверяющий должен увидеть
 * что-то интерактивное. Swagger UI позволяет вызвать любой эндпоинт прямо из
 * браузера (включая {@code /api/voice/command} после логина), поэтому корень
 * ведёт именно туда.
 *
 * <p>Альтернатива — отдавать HTML-страницу с описанием. Swagger выигрывает
 * тем, что он уже сгенерирован из кода и всегда актуален.
 */
@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "redirect:/swagger-ui/index.html";
    }
}