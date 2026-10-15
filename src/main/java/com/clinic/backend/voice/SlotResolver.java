package com.clinic.backend.voice;

import com.clinic.backend.appointment.AppointmentRepository;
import com.clinic.backend.doctor.DoctorRepository;
import com.clinic.backend.schedule.ScheduleEntry;
import com.clinic.backend.schedule.ScheduleProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Подбор свободного слота: пожелание пациента -> конкретный приём.
 *
 * Расписание берётся ТОЛЬКО через порт {@link ScheduleProvider}, поэтому
 * «из БД» и «из Excel» меняются одним флагом и этот класс об этом не знает.
 *
 * Правила записи (регламент, сценарий С-01 «Правила»):
 *   - горизонт записи — 14 календарных дней;
 *   - запись на сегодня — не позднее чем за 30 минут до начала приёма;
 *   - пациент не может занимать слот, который уже подтверждён.
 */
@Component
public class SlotResolver {

    /** Горизонт записи по регламенту. */
    public static final int HORIZON_DAYS = 14;
    /** На сегодня можно записаться не позже чем за это время до приёма. */
    public static final int SAME_DAY_MINUTES_BEFORE = 30;

    private final ScheduleProvider schedule;
    private final AppointmentRepository appointments;

    public SlotResolver(ScheduleProvider schedule, AppointmentRepository appointments) {
        this.schedule = schedule;
        this.appointments = appointments;
    }

    /** Есть ли вообще врачи по специальности. */
    public boolean specialtyStaffed(String specialty) {
        return specialty != null && !schedule.bySpecialty(specialty,
                LocalDate.now(), LocalDate.now()).isEmpty();
    }

    /** Названия специальностей, по которым ведётся запись. */
    public List<String> availableSpecialties() {
        return schedule.all(LocalDate.now(), LocalDate.now()).stream()
                .map(ScheduleEntry::specialty)
                .distinct()
                .sorted()
                .toList();
    }

    /** Источник расписания — для /api/voice/parser и диагностики. */
    public String sourceName() {
        return schedule.name();
    }

    public ScheduleProvider.Health health() {
        return schedule.health();
    }

    /** Первый свободный слот, подходящий под пожелание. */
    public Optional<Slot> findSlot(String specialty, TimeResolver.TimePreference pref,
                                   LocalDate today) {
        for (ScheduleEntry e : candidates(specialty, pref, today)) {
            if (free(e)) {
                return Optional.of(new Slot(e));
            }
        }
        return Optional.empty();
    }

    /** До трёх слотов для предложения пациенту (регламент §3.2: не более 3 вариантов). */
    public List<Slot> offerSlots(String specialty, TimeResolver.TimePreference pref,
                                 LocalDate today, int limit) {
        List<Slot> result = new ArrayList<>();
        for (ScheduleEntry e : candidates(specialty, pref, today)) {
            if (free(e)) {
                result.add(new Slot(e));
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Отбор приёмов под пожелание.
     *
     * Держится в пределах горизонта и отбрасывает прошедшее сегодняшнее время —
     * иначе можно предложить слот, который уже нельзя взять.
     */
    private List<ScheduleEntry> candidates(String specialty, TimeResolver.TimePreference pref,
                                           LocalDate today) {
        if (specialty == null) {
            return List.of();
        }
        LocalDate from = pref != null && pref.date() != null ? pref.date() : today;
        if (from.isBefore(today)) {
            from = today;
        }
        LocalDate to = from.plusDays(HORIZON_DAYS - 1);

        TimeResolver.TimePreference p = pref == null
                ? new TimeResolver.TimePreference(null, null, null, null, null, false, "")
                : pref;

        List<ScheduleEntry> all = schedule.bySpecialty(specialty, from, to);
        List<ScheduleEntry> result = new ArrayList<>();
        for (ScheduleEntry e : all) {
            if (p.excludeDay() != null && e.date().getDayOfWeek() == p.excludeDay()) {
                continue;
            }
            if (p.date() != null && !p.date().equals(e.date())) {
                continue;
            }
            if (!inPast(e, today)) {
                result.add(e);
            }
        }
        // Раньше — лучше; внутри дня — по времени приёма
        result.sort(Comparator.comparing(ScheduleEntry::date).thenComparing(ScheduleEntry::time));

        // Пожелание «как можно скорее» иначе игнорируется на этапе отбора
        if (p.soon() && result.size() > 3) {
            result = new ArrayList<>(result.subList(0, 3));
        }
        return result;
    }

    /** Слот сегодняшний, до которого уже нельзя записаться. */
    private boolean inPast(ScheduleEntry e, LocalDate today) {
        if (!e.date().equals(today)) {
            return false;
        }
        LocalDateTime start = e.date().atTime(java.time.LocalTime.parse(e.time()));
        return LocalDateTime.now().isAfter(
                start.minusMinutes(SAME_DAY_MINUTES_BEFORE));
    }

    private boolean free(ScheduleEntry e) {
        if (e.doctorId() == null) {
            return false;
        }
        return appointments
                .findByDoctorIdAndDateAndTimeAndStatus(e.doctorId(), e.date(), e.time(), "CONFIRMED")
                .isEmpty();
    }

    /** Свободный слот: та же структура, что раньше, но с источником расписания. */
    public record Slot(ScheduleEntry entry) {

        public Long doctorId() {
            return entry.doctorId();
        }

        public String fio() {
            return entry.fio();
        }

        public String specialty() {
            return entry.specialty();
        }

        public LocalDate date() {
            return entry.date();
        }

        public String time() {
            return entry.time();
        }

        public Integer cabinet() {
            return entry.cabinet();
        }
    }

    /** Фраза для озвучки: «в четверг, первого октября, в девять тридцать». */
    public static String saySlot(Slot slot) {
        String base = RussianTime.sayDateTime(slot.date(), slot.time());
        return slot.cabinet() != null ? base + ", кабинет " + sayNumber(slot.cabinet()) : base;
    }

    private static String sayNumber(Integer n) {
        return RussianTime.number(n);
    }

    /** Человекочитаемое расстояние до слота — для проверок и тестов. */
    public static Duration until(LocalDate date, String time, LocalDateTime now) {
        return Duration.between(now, date.atTime(java.time.LocalTime.parse(time)));
    }
}