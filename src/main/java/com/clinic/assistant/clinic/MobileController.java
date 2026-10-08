package com.clinic.assistant.clinic;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Эндпоинты, форма ответа которых повторяет data model Android-приложения
 * ({@code com.clinic.clinicapp.data.model}): id — строки, поля name/availableSlots.
 *
 * <pre>GET /api/mobile/doctors?fromDate=2026-10-08&days=14</pre>
 */
@RestController
@RequestMapping("/api/mobile")
@Tag(name = "Мобильное приложение")
public class MobileController {

    private final DoctorViewService views;

    public MobileController(DoctorViewService views) {
        this.views = views;
    }

    @GetMapping("/doctors")
    @Operation(summary = "Врачи со слотами для мобильного приложения")
    public List<DoctorViewService.MobileDoctor> doctors(
            @RequestParam(required = false) String fromDate,
            @RequestParam(defaultValue = "14") int days) {
        LocalDate from = (fromDate == null || fromDate.isBlank()) ? LocalDate.now() : LocalDate.parse(fromDate);
        return views.mobileDoctors(from, days);
    }
}
