package com.clinic.backend.doctor;

import com.clinic.backend.appointment.AppointmentRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Контракт фронтенда (js/api.js):
 *   GET /api/doctors                  -> врачи {id, fio, specialty, time, days}
 *   GET /api/schedule?date=YYYY-MM-DD -> врачи, работающие в эту дату
 *   GET /api/doctors/unique-times     -> {ok, time} уникальность времени приёма
 *   GET /api/employees                -> таблица сотрудников
 *
 * Контракт мобильного приложения:
 *   GET /api/doctors/{id}/slots?from=YYYY-MM-DD&days=N -> слоты {slotId, date, time, available}
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Doctors & Schedule", description = "Врачи, расписание по дате, слоты, уникальность времени")
public class DoctorController {

    private final DoctorRepository doctors;
    private final AppointmentRepository appointments;

    public DoctorController(DoctorRepository doctors, AppointmentRepository appointments) {
        this.doctors = doctors;
        this.appointments = appointments;
    }

    @GetMapping("/doctors")
    public List<DoctorDto> getDoctors() {
        return doctors.findAllByOrderByFioAsc().stream().map(DoctorDto::from).toList();
    }

    /** Расписание на дату: врачи, работающие в этот день, отсортированные по времени. */
    @GetMapping("/schedule")
    public List<DoctorDto> getSchedule(@RequestParam("date") String date) {
        LocalDate parsed = parseDate(date);
        return doctors.findAll().stream()
                .filter(d -> d.worksOn(parsed))
                .sorted(Comparator.comparing(Doctor::getTime))
                .map(DoctorDto::from)
                .toList();
    }

    /** Уникальность времени приёма среди врачей (badge во фронтенде). */
    @GetMapping("/doctors/unique-times")
    public Map<String, Object> uniqueTimes() {
        Map<String, Long> counts = doctors.findAll().stream()
                .collect(java.util.stream.Collectors.groupingBy(Doctor::getTime, java.util.stream.Collectors.counting()));
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            if (e.getValue() > 1) {
                return Map.of("ok", false, "time", e.getKey());
            }
        }
        return Map.of("ok", true);
    }

    /** Таблица сотрудников: ФИО, специальность, ближайшее время записи. */
    @GetMapping("/employees")
    public List<Map<String, Object>> getEmployees() {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Doctor d : doctors.findAllByOrderByFioAsc()) {
            result.add(Map.of(
                    "id", d.getId(),
                    "fio", d.getFio(),
                    "specialty", d.getSpecialty(),
                    "nextAppointment", nearestSlot(d, today)));
        }
        return result;
    }

    /**
     * Слоты врача (мобильное приложение): ближайшие N дней, каждый рабочий день —
     * слот в время приёма врача, с пометкой занятости.
     */
    @GetMapping("/doctors/{id}/slots")
    public List<Map<String, Object>> slots(
            @PathVariable("id") Long id,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "days", required = false, defaultValue = "14") int days) {
        Doctor doctor = doctors.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Врач не найден"));
        LocalDate start = from == null ? LocalDate.now() : parseDate(from);
        int window = Math.min(Math.max(days, 1), 60);
        List<Map<String, Object>> slots = new ArrayList<>();
        for (int i = 0; i < window; i++) {
            LocalDate date = start.plusDays(i);
            if (!doctor.worksOn(date)) {
                continue;
            }
            boolean taken = appointments
                    .findByDoctorIdAndDateAndTimeAndStatus(doctor.getId(), date, doctor.getTime(), "CONFIRMED")
                    .isPresent();
            slots.add(Map.of(
                    "slotId", slotId(doctor, date),
                    "date", date.toString(),
                    "time", doctor.getTime(),
                    "available", !taken));
        }
        return slots;
    }

    private static LocalDate parseDate(String date) {
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Неверный формат даты, ожидается YYYY-MM-DD: " + date);
        }
    }

    /** Ближайший свободный слот врача: "YYYY-MM-DD HH:mm" либо "нет свободных". */
    private String nearestSlot(Doctor d, LocalDate from) {
        LocalDate candidate = from;
        for (int i = 0; i < 60; i++) {
            if (d.worksOn(candidate)) {
                boolean taken = appointments
                        .findByDoctorIdAndDateAndTimeAndStatus(d.getId(), candidate, d.getTime(), "CONFIRMED")
                        .isPresent();
                if (!taken) {
                    return candidate.toString() + " " + d.getTime();
                }
            }
            candidate = candidate.plusDays(1);
        }
        return "нет свободных";
    }

    /** Формат слота "d{id}-{date}-{HHmm}" — единый для слотов, брони и голосового подбора. */
    public static String slotId(Doctor d, LocalDate date) {
        return "d" + d.getId() + "-" + date + "-" + d.getTime().replace(":", "");
    }
}
