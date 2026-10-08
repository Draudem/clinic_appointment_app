package com.clinic.assistant.clinic;

import com.clinic.assistant.common.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Разворачивает еженедельное расписание ({@link ScheduleSlot}) в конкретные
 * даты/времена и показывает, какие из них уже заняты ({@link Appointment}).
 *
 * <p>Это «движок» расписания: календарь фронтенда, таблица сотрудников
 * и голосовой конвейер работают через одни и те же методы — данные всегда
 * согласованы.</p>
 */
@Service
public class ScheduleService {

    private static final int MAX_RANGE_DAYS = 400;

    private final ScheduleSlotRepository scheduleSlotRepository;
    private final AppointmentRepository appointmentRepository;
    private final DoctorRepository doctorRepository;

    public ScheduleService(ScheduleSlotRepository scheduleSlotRepository,
                           AppointmentRepository appointmentRepository,
                           DoctorRepository doctorRepository) {
        this.scheduleSlotRepository = scheduleSlotRepository;
        this.appointmentRepository = appointmentRepository;
        this.doctorRepository = doctorRepository;
    }

    /**
     * Конкретное окно приёма.
     *
     * @param slotId      id записи расписания (schedule_slot)
     * @param appointmentId id активной записи пациента, если слот занят
     * @param booked      занят ли слот
     */
    public record SlotOccurrence(Long slotId,
                                 Long appointmentId,
                                 Long doctorId,
                                 String fio,
                                 String specialty,
                                 LocalDate date,
                                 LocalTime time,
                                 boolean booked) {

        public LocalDateTime dateTime() {
            return LocalDateTime.of(date, time);
        }
    }

    /** Все окна приёма всех врачей в диапазоне дат. */
    @Transactional(readOnly = true)
    public List<SlotOccurrence> occurrences(LocalDate from, LocalDate to) {
        return occurrences(from, to, null);
    }

    /** Окна приёма указанных врачей (null = все). */
    @Transactional(readOnly = true)
    public List<SlotOccurrence> occurrences(LocalDate from, LocalDate to, Collection<Long> doctorIds) {
        if (from == null || to == null) {
            throw new BadRequestException("Укажите даты в диапазоне (from/to)");
        }
        if (to.isBefore(from)) {
            throw new BadRequestException("Конец диапазона раньше начала");
        }
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new BadRequestException("Слишком большой диапазон дат (максимум " + MAX_RANGE_DAYS + " дней)");
        }

        List<ScheduleSlot> slots = (doctorIds == null || doctorIds.isEmpty())
                ? scheduleSlotRepository.findAll()
                : scheduleSlotRepository.findByDoctorIdIn(doctorIds);
        if (slots.isEmpty()) {
            return List.of();
        }

        Set<Long> ids = slots.stream().map(s -> s.getDoctor().getId()).collect(Collectors.toSet());
        Map<String, Long> busy = busyIndex(from, to, ids);

        List<SlotOccurrence> result = new ArrayList<>();
        for (ScheduleSlot slot : slots) {
            Doctor doctor = slot.getDoctor();
            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
                if (date.getDayOfWeek().getValue() != slot.getWeekday()) {
                    continue;
                }
                String key = key(doctor.getId(), date, slot.getStartTime());
                Long appointmentId = busy.get(key);
                result.add(new SlotOccurrence(
                        slot.getId(),
                        appointmentId,
                        doctor.getId(),
                        doctor.getFio(),
                        doctor.getSpecialty().getName(),
                        date,
                        slot.getStartTime(),
                        appointmentId != null));
            }
        }
        result.sort(Comparator.comparing(SlotOccurrence::date).thenComparing(SlotOccurrence::time));
        return result;
    }

    /** Окна приёма одного врача. */
    @Transactional(readOnly = true)
    public List<SlotOccurrence> occurrencesForDoctor(Long doctorId, LocalDate from, LocalDate to) {
        return occurrences(from, to, List.of(doctorId));
    }

    /** Есть ли у врача еженедельное окно в этот день/время (для валидации брони). */
    @Transactional(readOnly = true)
    public boolean hasWeeklySlot(Long doctorId, LocalDate date, LocalTime time) {
        return scheduleSlotRepository.findByDoctor_Id(doctorId).stream()
                .anyMatch(s -> s.getWeekday() == date.getDayOfWeek().getValue()
                        && s.getStartTime().equals(time));
    }

    /** Все недельные расписания (для построения DTO «дни недели»). */
    @Transactional(readOnly = true)
    public List<ScheduleSlot> allSlots() {
        return scheduleSlotRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Doctor> allDoctors() {
        return doctorRepository.findByActiveTrue();
    }

    /** Индекс занятых слотов: "doctorId|date|time" → id записи пациента. */
    private Map<String, Long> busyIndex(LocalDate from, LocalDate to, Set<Long> doctorIds) {
        List<Appointment> busy = appointmentRepository
                .findByStatusAndDateBetweenAndDoctorIdIn(AppointmentStatus.BOOKED, from, to, doctorIds);
        Map<String, Long> index = new HashMap<>();
        for (Appointment a : busy) {
            index.put(key(a.getDoctor().getId(), a.getDate(), a.getStartTime()), a.getId());
        }
        return index;
    }

    private static String key(Long doctorId, LocalDate date, LocalTime time) {
        return doctorId + "|" + date + "|" + time;
    }
}
