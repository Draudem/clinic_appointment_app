package com.clinic.backend.voice;

import com.clinic.backend.appointment.AppointmentRepository;
import com.clinic.backend.doctor.DoctorRepository;
import com.clinic.backend.mis.MisClock;
import com.clinic.backend.schedule.ScheduleEntry;
import com.clinic.backend.schedule.ScheduleProvider;
import org.springframework.stereotype.Component;
import com.clinic.backend.rag.ClinicalKnowledgeBase;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

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
    private final MisClock clock;

    public SlotResolver(ScheduleProvider schedule, AppointmentRepository appointments, MisClock clock) {
        this.schedule = schedule;
        this.appointments = appointments;
        this.clock = clock;
    }

    /** Есть ли вообще врачи по специальности. */
    public boolean specialtyStaffed(String specialty) {
        if (specialty == null) return false;
        LocalDate today = clock.today();
        // Смотрим не только «сегодня», а весь горизонт записи: воскресенье —
        // не повод говорить «приёма нет», если завтра стоматолог работает.
        return !schedule.bySpecialty(specialty, today, today.plusDays(HORIZON_DAYS - 1)).isEmpty();
    }

    /** Названия специальностей, по которым ведётся запись. */
    public List<String> availableSpecialties() {
        LocalDate from = clock.today();
        LocalDate to = from.plusDays(14);
        Set<String> core = Set.of("Терапевт", "Педиатр", "Стоматолог-терапевт",
                "Лор (отоларинголог)", "Офтальмолог", "Хирург", "Акушер-гинеколог",
                "Невролог", "Кардиолог", "Дерматовенеролог", "Эндокринолог");
        return schedule.all(from, to).stream()
                .map(ScheduleEntry::specialty)
                .filter(Objects::nonNull)
                .filter(core::contains)
                .distinct()
                .sorted()
                .limit(10)
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
     *
     * ВАЖНО: если после применения горизонта from > to (пациент назвал дату
     * дальше, чем 14 дней), возвращаем ПУСТО ЯВНО. Раньше это получалось
     * «случайно»: провайдер возвращал пустой список из-за инвертированного
     * диапазона, и тест на далёкую дату проходил по случайной причине.
     * Теперь поведение выражено в коде и не зависит от реализации провайдера.
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
        // Горизонт отсчитывается от СЕГОДНЯ, а не от названной даты. Раньше окно
        // просто сдвигалось: пациент называл дату через полгода — и мы предлагали
        // запись на неё, хотя поликлиника открывает расписание на 14 дней вперёд.
        LocalDate to = from.plusDays(HORIZON_DAYS - 1);
        LocalDate maxDate = today.plusDays(HORIZON_DAYS - 1);
        if (to.isAfter(maxDate)) {
            to = maxDate;
        }
        if (from.isAfter(to)) {
            // пожелание за пределами горизонта: ответ «записей нет»,
            // а не «случайно пусто у провайдера»
            return List.of();
        }

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
        try {
            LocalDateTime start = e.date().atTime(java.time.LocalTime.parse(e.time()));
            return clock.now().isAfter(start.minusMinutes(SAME_DAY_MINUTES_BEFORE));
        } catch (Exception ex) {
            return false;
        }
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