package com.clinic.assistant.clinic;

import com.clinic.assistant.common.BadRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Расписание: приёмы на дату, бейджи календаря, проверка уникальности времени.
 *
 * <pre>
 * GET /api/schedule?date=2026-10-08        → [{id, fio, specialty, time, days, available}]
 * GET /api/calendar?year=2026&month=10     → [{"date": "2026-10-01", "count": 4}, ...]
 * GET /api/schedule/unique-times           → {ok: true} | {ok: false, time: "09:00"}
 * </pre>
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Расписание")
public class ScheduleController {

    private final DoctorViewService views;
    private final ScheduleService scheduleService;

    public ScheduleController(DoctorViewService views, ScheduleService scheduleService) {
        this.views = views;
        this.scheduleService = scheduleService;
    }

    @GetMapping("/schedule")
    @Operation(summary = "Приёмы на конкретную дату")
    public List<DoctorViewService.ScheduleItemDto> schedule(@RequestParam String date) {
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(date);
        } catch (Exception e) {
            throw new BadRequestException("Параметр date должен быть в формате YYYY-MM-DD");
        }
        return views.scheduleFor(parsed);
    }

    @GetMapping("/calendar")
    @Operation(summary = "Количество приёмов по дням месяца (бейджи календаря)")
    public List<Map<String, Object>> calendar(@RequestParam int year, @RequestParam int month) {
        if (month < 1 || month > 12) {
            throw new BadRequestException("Месяц должен быть от 1 до 12");
        }
        LocalDate first = LocalDate.of(year, month, 1);
        LocalDate last = first.withDayOfMonth(first.lengthOfMonth());
        Map<String, Integer> counts = views.monthCounts(first, last);

        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.toString());
            row.put("count", counts.getOrDefault(d.toString(), 0));
            result.add(row);
        }
        return result;
    }

    @GetMapping("/schedule/unique-times")
    @Operation(summary = "Уникально ли время приёма у разных врачей (проверка фронтенда)")
    public Map<String, Object> uniqueTimes() {
        // время считается «занятым разными врачами», если оно встречается у 2+ врачей
        Map<LocalTime, java.util.Set<Long>> doctorsByTime = new java.util.TreeMap<>();
        scheduleService.allSlots().forEach(s ->
                doctorsByTime.computeIfAbsent(s.getStartTime(), t -> new java.util.HashSet<>())
                        .add(s.getDoctor().getId()));
        return doctorsByTime.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(Map.Entry::getKey)
                .min(LocalTime::compareTo)
                .map(t -> Map.<String, Object>of("ok", false, "time", t.toString()))
                .orElseGet(() -> Map.<String, Object>of("ok", true));
    }
}
