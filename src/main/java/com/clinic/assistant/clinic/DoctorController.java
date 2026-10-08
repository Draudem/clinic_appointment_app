package com.clinic.assistant.clinic;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Врачи: общий список, карточка, слоты (для мобильного приложения).
 *
 * <pre>
 * GET /api/doctors                → [{id, fio, specialty, time, days, nextSlot}]
 * GET /api/doctors/{id}           → {...}
 * GET /api/doctors/{id}/slots?from=2026-10-08&days=14
 *                                  → [{id, date, time, isAvailable}]
 * </pre>
 */
@RestController
@RequestMapping("/api/doctors")
@Tag(name = "Врачи")
public class DoctorController {

    private final DoctorViewService views;
    private final ScheduleService scheduleService;

    public DoctorController(DoctorViewService views, ScheduleService scheduleService) {
        this.views = views;
        this.scheduleService = scheduleService;
    }

    @GetMapping
    @Operation(summary = "Список врачей (для календаря и таблицы сотрудников)")
    public List<DoctorViewService.DoctorDto> all() {
        return views.doctors();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Врач по id")
    public DoctorViewService.DoctorDto one(@PathVariable Long id) {
        return views.doctor(id);
    }

    @GetMapping("/{id}/slots")
    @Operation(summary = "Конкретные слоты врача на диапазон дат (мобильное приложение)")
    public List<DoctorViewService.MobileSlot> slots(@PathVariable Long id,
                                                    @RequestParam(required = false) String from,
                                                    @RequestParam(defaultValue = "14") int days) {
        LocalDate startDate = (from == null || from.isBlank()) ? LocalDate.now() : LocalDate.parse(from);
        return scheduleService.occurrencesForDoctor(id, startDate, startDate.plusDays(Math.max(1, days) - 1))
                .stream()
                .map(o -> new DoctorViewService.MobileSlot(
                        o.slotId() + "@" + o.date() + "T" + o.time(),
                        o.date().toString(),
                        o.time().toString(),
                        !o.booked()))
                .toList();
    }
}
