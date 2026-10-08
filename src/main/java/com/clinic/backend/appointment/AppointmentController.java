package com.clinic.backend.appointment;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * ЗАГЛУШКА: список записей пациента.
 * Нужна, чтобы проверить защищённый запрос:
 *   без JWT  -> 401, с JWT -> 200 + список.
 *
 * Дальше заменяется на AppointmentService (JPA) без правок контракта:
 * форма ответа {appointments: [...]} останется той же.
 */
@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    @GetMapping
    public Map<String, List<Map<String, Object>>> myAppointments() {
        List<Map<String, Object>> stub = List.of(
                Map.of(
                        "id", 1,
                        "doctorName", "Иванов Пётр Сергеевич",
                        "specialty", "Кардиолог",
                        "date", "2026-10-10",
                        "time", "09:00"),
                Map.of(
                        "id", 2,
                        "doctorName", "Попова Елена Игоревна",
                        "specialty", "Терапевт",
                        "date", "2026-10-13",
                        "time", "10:30"));
        return Map.of("appointments", stub);
    }
}
