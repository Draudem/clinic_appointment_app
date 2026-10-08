package com.clinic.assistant.clinic;

import com.clinic.assistant.security.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Записи на приём (подтверждение брони после голосового запроса).
 *
 * <pre>
 * POST   /api/appointments            {doctorId, date, time}  → 201
 * GET    /api/appointments?date=&doctorId=  свои (или все — админ)
 * DELETE /api/appointments/{id}                               → 204
 * </pre>
 */
@RestController
@RequestMapping("/api/appointments")
@Tag(name = "Записи")
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Записаться на приём")
    public AppointmentService.AppointmentDto create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody AppointmentService.CreateRequest request) {
        return appointmentService.create(principal, request);
    }

    @GetMapping
    @Operation(summary = "Список записей (пациент — свои, администратор — все)")
    public List<AppointmentService.AppointmentDto> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) Long doctorId) {
        LocalDate parsed = (date == null || date.isBlank()) ? null : LocalDate.parse(date);
        return appointmentService.list(principal, parsed, doctorId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Отменить запись")
    public void cancel(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable Long id) {
        appointmentService.cancel(principal, id);
    }
}
