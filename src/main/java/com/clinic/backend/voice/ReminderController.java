package com.clinic.backend.voice;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/voice/reminder")
@Tag(name = "Voice: Reminders", description = "С-10 Исходящие напоминания")
public class ReminderController {

    private static final Logger log = LoggerFactory.getLogger(ReminderController.class);

    private final ReminderService reminderService;
    private final AsteriskCaller asteriskCaller;

    public ReminderController(ReminderService reminderService, AsteriskCaller asteriskCaller) {
        this.reminderService = reminderService;
        this.asteriskCaller = asteriskCaller;
    }

    @PostMapping("/plan")
    @Operation(summary = "План обзвона на завтра")
    public ResponseEntity<Map<String, Object>> plan() {
        List<ReminderService.ReminderTask> tasks = reminderService.planForTomorrow();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "OK");
        response.put("total_tasks", tasks.size());
        response.put("tasks", tasks);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/start")
    @Operation(summary = "Запустить тестовый исходящий обзвон (С-10)")
    public ResponseEntity<Map<String, Object>> start() {
        List<ReminderService.ReminderTask> tasks = reminderService.planForTomorrow();
        if (tasks.isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "ERROR", "message", "Нет записей на завтра для обзвона"));
        }

        // Берем первую запись из плана для теста
        ReminderService.ReminderTask task = tasks.get(0);
        log.info("С-10: Ручной запуск обзвона по записи #{} ({})", task.appointmentId(), task.patientName());

        boolean success = asteriskCaller.originateCall(task.appointmentId());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", success ? "CALLING" : "ERROR");
        response.put("appointmentId", task.appointmentId());
        response.put("patientName", task.patientName());
        return ResponseEntity.ok(response);
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

    public record MarkRequest(Long appointmentId, String resultCode) {
    }
}