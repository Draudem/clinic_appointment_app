package com.clinic.backend.voice;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * С-10 «Исходящий звонок-напоминание» — каркас.
 *
 * <p>Регламент РГ-ТЕЛ-01: напоминать за 1 рабочий день, до 2 попыток с интервалом
 * 2 часа, при недозвоне — SMS. Целевой эффект — снижение неявок с 18–25% до ≤12%.
 *
 * <p>Реализован каркас: эндпоинт возвращает план звонка (когда, сколько попыток,
 * что делать при отказе). <b>Не реализовано</b>: Asterisk {@code Originate},
 * SMS-шлюз, cron-выборка записей на завтра.
 *
 * <p>План: на пилоте обзвон идёт вручную через регистратора; автоматизация —
 * после того как С-01 покажет метрики.
 */
@RestController
@RequestMapping("/api/voice")
@Tag(name = "Voice", description = "Голосовая запись + каркас С-10")
public class ReminderController {

    @PostMapping("/reminder")
    @Operation(summary = "План исходящего напоминания (каркас С-10)")
    public ResponseEntity<Map<String, Object>> reminder() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("status", "NOT_IMPLEMENTED");
        plan.put("scenario", "С-10");
        plan.put("regulation", "РГ-ТЕЛ-01, раздел С-10");
        plan.put("when", "за 1 рабочий день до приёма; для понедельника — в пятницу");
        plan.put("attempts", 2);
        plan.put("intervalHours", 2);
        plan.put("onRefusal", "перенос или отмена, слот → лист ожидания");
        plan.put("onNoAnswer", "SMS-напоминание");
        plan.put("targetEffect", "неявки 18–25% → ≤12%");
        plan.put("missing", List.of(
                "Asterisk Originate для исходящего звонка",
                "SMS-шлюз",
                "Cron-планировщик выборки записей на завтра"));
        plan.put("workaround", "на пилоте — ручной обзвон регистратором");
        return ResponseEntity.status(501).body(plan);
    }
}