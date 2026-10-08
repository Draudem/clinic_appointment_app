package com.clinic.assistant.clinic;

import com.clinic.assistant.clinic.ScheduleService.SlotOccurrence;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Собирает DTO для фронтенда и мобильного приложения из расписания и записей.
 *
 * <p>«Ближайшее время записи» — первое свободное окно приёма врача
 * с текущего момента (ищем на 60 дней вперёд).</p>
 */
@Service
public class DoctorViewService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int NEXT_SLOT_HORIZON_DAYS = 60;

    private final ScheduleService scheduleService;

    public DoctorViewService(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /** Врач для календаря/списков: {id, fio, specialty, time, days, nextSlot}. */
    public record DoctorDto(Long id, String fio, String specialty, String time,
                            List<Integer> days, String nextSlot) {
    }

    /** Строка таблицы «Сотрудники»: ФИО, специальность, ближайшее время записи. */
    public record EmployeeDto(Long doctorId, String fio, String specialty,
                              String nextSlot, String time, List<Integer> days) {
    }

    /** Строка расписания на конкретную дату. */
    public record ScheduleItemDto(Long id, String fio, String specialty, String time,
                                  List<Integer> days, String date, Long slotId, boolean available) {
    }

    /** Слот для мобильного приложения. */
    public record MobileSlot(String id, String date, String time, boolean isAvailable) {
    }

    /** Врач для мобильного приложения (совпадает с data model Android-приложения). */
    public record MobileDoctor(String id, String name, String specialty, List<MobileSlot> availableSlots) {
    }

    /** Ближайшая дата/время свободного приёма по всем врачам. */
    private record NextFree(Long doctorId, LocalDateTime dateTime) {
    }

    @Transactional(readOnly = true)
    public List<DoctorDto> doctors() {
        Views views = build();
        return views.doctors().stream()
                .map(d -> new DoctorDto(
                        d.getId(),
                        d.getFio(),
                        d.getSpecialty().getName(),
                        views.weeklyTime(d.getId()),
                        views.weeklyDays(d.getId()),
                        views.nextSlotText(d.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public DoctorDto doctor(Long id) {
        Views views = build();
        Doctor doctor = views.doctorById(id)
                .orElseThrow(() -> new com.clinic.assistant.common.NotFoundException("Врач не найден: " + id));
        return new DoctorDto(
                doctor.getId(),
                doctor.getFio(),
                doctor.getSpecialty().getName(),
                views.weeklyTime(doctor.getId()),
                views.weeklyDays(doctor.getId()),
                views.nextSlotText(doctor.getId()));
    }

    @Transactional(readOnly = true)
    public List<EmployeeDto> employees() {
        Views views = build();
        return views.doctors().stream()
                .map(d -> new EmployeeDto(
                        d.getId(),
                        d.getFio(),
                        d.getSpecialty().getName(),
                        views.nextSlotText(d.getId()),
                        views.weeklyTime(d.getId()),
                        views.weeklyDays(d.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ScheduleItemDto> scheduleFor(LocalDate date) {
        Views views = build();
        LocalDateTime now = LocalDateTime.now();
        return scheduleService.occurrences(date, date).stream()
                .map(o -> new ScheduleItemDto(
                        o.doctorId(),
                        o.fio(),
                        o.specialty(),
                        o.time().format(TIME),
                        views.weeklyDays(o.doctorId()),
                        o.date().format(DATE),
                        o.slotId(),
                        !o.booked() && o.dateTime().isAfter(now)))
                .toList();
    }

    /** Число приёмов по дням месяца (для бейджей календаря). */
    @Transactional(readOnly = true)
    public Map<String, Integer> monthCounts(LocalDate monthStart, LocalDate monthEnd) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SlotOccurrence o : scheduleService.occurrences(monthStart, monthEnd)) {
            counts.merge(o.date().format(DATE), 1, Integer::sum);
        }
        return counts;
    }

    /** Мобильные doctors + слоты на диапазон дат. */
    @Transactional(readOnly = true)
    public List<MobileDoctor> mobileDoctors(LocalDate from, int days) {
        Views views = build();
        LocalDate to = from.plusDays(Math.max(1, days) - 1);
        LocalDateTime now = LocalDateTime.now();
        Map<Long, List<SlotOccurrence>> byDoctor = scheduleService.occurrences(from, to).stream()
                .collect(Collectors.groupingBy(SlotOccurrence::doctorId, LinkedHashMap::new, Collectors.toList()));

        List<MobileDoctor> result = new ArrayList<>();
        for (Doctor doctor : views.doctors()) {
            List<SlotOccurrence> list = byDoctor.getOrDefault(doctor.getId(), List.of());
            List<MobileSlot> slots = list.stream()
                    .map(o -> new MobileSlot(
                            o.slotId() + "@" + o.date() + "T" + o.time(),
                            o.date().format(DATE),
                            o.time().format(TIME),
                            !o.booked() && o.dateTime().isAfter(now)))
                    .toList();
            result.add(new MobileDoctor(
                    String.valueOf(doctor.getId()),
                    doctor.getFio(),
                    doctor.getSpecialty().getName(),
                    slots));
        }
        return result;
    }

    /** Первая свободная дата/время по всем врачам. */
    @Transactional(readOnly = true)
    public NextFree nextFree() {
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        return scheduleService.occurrences(today, today.plusDays(NEXT_SLOT_HORIZON_DAYS)).stream()
                .filter(o -> !o.booked() && o.dateTime().isAfter(now))
                .min(Comparator.comparing(SlotOccurrence::dateTime))
                .map(o -> new NextFree(o.doctorId(), o.dateTime()))
                .orElse(null);
    }

    // ------------------------------------------------------------------

    /** Предвычисленные представления расписания (дни недели, время, ближайший слот). */
    private Views build() {
        List<Doctor> doctors = scheduleService.allDoctors();
        List<ScheduleSlot> slots = scheduleService.allSlots();

        Map<Long, List<Integer>> days = new LinkedHashMap<>();
        Map<Long, String> weeklyTime = new LinkedHashMap<>();
        Map<Long, List<ScheduleSlot>> slotsByDoctor = slots.stream()
                .collect(Collectors.groupingBy(s -> s.getDoctor().getId(), LinkedHashMap::new, Collectors.toList()));

        for (Doctor doctor : doctors) {
            List<ScheduleSlot> list = slotsByDoctor.getOrDefault(doctor.getId(), List.of());
            List<Integer> jsDays = list.stream()
                    .map(ScheduleSlot::getWeekday)
                    .map(DoctorViewService::isoToJsDay)
                    .distinct()
                    .sorted()
                    .toList();
            days.put(doctor.getId(), jsDays);
            list.stream()
                    .map(ScheduleSlot::getStartTime)
                    .min(LocalTime::compareTo)
                    .ifPresent(t -> weeklyTime.put(doctor.getId(), t.format(TIME)));
        }

        // ближайшие свободные окна
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        Map<Long, LocalDateTime> nextFree = new LinkedHashMap<>();
        scheduleService.occurrences(today, today.plusDays(NEXT_SLOT_HORIZON_DAYS)).stream()
                .filter(o -> !o.booked() && o.dateTime().isAfter(now))
                .forEach(o -> nextFree.merge(o.doctorId(), o.dateTime(),
                        (a, b) -> a.isBefore(b) ? a : b));

        return new Views(doctors, days, weeklyTime, nextFree);
    }

    private record Views(List<Doctor> doctors,
                         Map<Long, List<Integer>> days,
                         Map<Long, String> weeklyTime,
                         Map<Long, LocalDateTime> nextFree) {

        java.util.Optional<Doctor> doctorById(Long id) {
            return doctors.stream().filter(d -> d.getId().equals(id)).findFirst();
        }

        List<Integer> weeklyDays(Long doctorId) {
            return days.getOrDefault(doctorId, List.of());
        }

        String weeklyTime(Long doctorId) {
            return weeklyTime.get(doctorId);
        }

        String nextSlotText(Long doctorId) {
            LocalDateTime next = nextFree.get(doctorId);
            return next == null ? null : next.format(DATE_TIME);
        }
    }

    /** ISO (1=Пн…7=Вс) → номер дня в JS (0=Вс…6=Сб) — так считает фронтенд. */
    public static int isoToJsDay(int isoDay) {
        return isoDay == 7 ? 0 : isoDay;
    }

    /** JS-день (0=Вс…6=Сб) → ISO (1=Пн…7=Вс). */
    public static int jsToIsoDay(int jsDay) {
        return jsDay == 0 ? 7 : jsDay;
    }
}
