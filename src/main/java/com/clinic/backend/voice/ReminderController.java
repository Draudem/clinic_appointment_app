package com.clinic.backend.voice;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * С-10 «Исходящий звонок-напоминание».
 *
 * <p>Регламент РГ-ТЕЛ-01: напоминать за 1 рабочий день, до 2 попыток с интервалом
 * 2 часа, при недозвоне — SMS. Целевой эффект — снижение неявок с 18–25% до ≤12%.
 *
 * <p>Реализовано: планирование задач на завтра и фиксация результатов обзвона.
 * Интеграция с Asterisk (создание реального исходящего вызова) — в разработке.
 */
@RestController
@RequestMapping("/api/voice/reminder")
@Tag(name = "Voice: Reminders", description = "С-10 Исходящие напоминания")
public class ReminderController {

    private final ReminderService reminderService;

    public ReminderController(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    @PostMapping("/plan")
    @Operation(summary = "План обзвона на завтра")
    public ResponseEntity<Map<String, Object>> plan() {
        List<ReminderService.ReminderTask> tasks = reminderService.planForTomorrow();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "OK");
        response.put("scenario", "С-10");
        response.put("regulation", "РГ-ТЕЛ-01, раздел С-10");
        response.put("date", java.time.LocalDate.now().plusDays(1).toString());
        response.put("total_tasks", tasks.size());
        response.put("tasks", tasks);

        return ResponseEntity.ok(response);
    }

    public record MarkRequest(Long appointmentId, String resultCode) {
    }

    @PostMapping("/mark")
    @Operation(summary = "Зафиксировать результат обзвона")
    public ResponseEntity<Map<String, Object>> mark(@RequestBody MarkRequest request) {
        reminderService.markResult(request.appointmentId(), request.resultCode());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("appointmentId", request.appointmentId());
        response.put("resultCode", request.resultCode());
        response.put("recorded", true);

        return ResponseEntity.ok(response);
    }
}