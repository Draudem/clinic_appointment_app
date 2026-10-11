package com.clinic.backend.appointment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Записи пациентов (контракт мобильного приложения):
 *   GET    /api/appointments             — свои записи (админ — все)
 *   POST   /api/appointments             — создать запись {doctorId, date, time?}
 *   DELETE /api/appointments/{id}        — отменить запись
 */
@RestController
@RequestMapping("/api/appointments")
@Tag(name = "Appointments", description = "Записи пациентов: список, создание, отмена")
public class AppointmentController {

    private final AppointmentService service;

    public AppointmentController(AppointmentService service) {
        this.service = service;
    }

    public record BookRequest(
            @NotNull(message = "doctorId обязателен") Long doctorId,
            @NotBlank(message = "date обязательна") String date,
            String time) {
    }

    @GetMapping
    public java.util.List<Map<String, Object>> myAppointments(Authentication authentication) {
        boolean isAdmin = authentication.getAuthorities()
                .contains(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return service.listFor(authentication.getName(), isAdmin);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> book(
            Authentication authentication,
            @Valid @RequestBody BookRequest request) {
        LocalDate date;
        try {
            date = LocalDate.parse(request.date());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Неверный формат даты, ожидается YYYY-MM-DD: " + request.date());
        }
        Map<String, Object> created = service.book(authentication.getName(), request.doctorId(), date, request.time());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> cancel(
            Authentication authentication,
            @PathVariable("id") Long id) {
        boolean isAdmin = authentication.getAuthorities()
                .contains(new SimpleGrantedAuthority("ROLE_ADMIN"));
        service.cancel(authentication.getName(), id, isAdmin);
        return ResponseEntity.ok(Map.of("message", "Запись отменена"));
    }
}
