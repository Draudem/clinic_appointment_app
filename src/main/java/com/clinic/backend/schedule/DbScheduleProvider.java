package com.clinic.backend.schedule;

import com.clinic.backend.doctor.Doctor;
import com.clinic.backend.doctor.DoctorRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Расписание из таблицы doctors.
 *
 * Текущая модель БД описывает приём одним временем на врача и списком дней
 * недели ({@code doctors.time}, {@code doctors.days}), поэтому здесь дни
 * недели разворачиваются в конкретные даты. Если позже появится отдельная
 * таблица слотов, меняется только этот класс — пор остаётся прежним.
 */
public class DbScheduleProvider implements ScheduleProvider {

    private final DoctorRepository doctors;

    public DbScheduleProvider(DoctorRepository doctors) {
        this.doctors = doctors;
    }

    @Override
    public String name() {
        return "db:doctors";
    }

    @Override
    public List<ScheduleEntry> bySpecialty(String specialty, LocalDate from, LocalDate to) {
        List<ScheduleEntry> result = new ArrayList<>();
        if (specialty == null) {
            return result;
        }
        for (Doctor d : doctors.findBySpecialty(specialty)) {
            result.addAll(expand(d, from, to));
        }
        return result;
    }

    @Override
    public List<ScheduleEntry> all(LocalDate from, LocalDate to) {
        List<ScheduleEntry> result = new ArrayList<>();
        for (Doctor d : doctors.findAll()) {
            result.addAll(expand(d, from, to));
        }
        return result;
    }

    @Override
    public List<ScheduleEntry> byDoctorFio(String fio, LocalDate from, LocalDate to) {
        List<ScheduleEntry> result = new ArrayList<>();
        for (Doctor d : doctors.findAll()) {
            if (d.getFio().equalsIgnoreCase(fio)) {
                result.addAll(expand(d, from, to));
            }
        }
        return result;
    }

    @Override
    public Optional<ScheduleEntry> exact(String fio, LocalDate date, String time) {
        if (fio == null || date == null || time == null) {
            return Optional.empty();
        }
        return byDoctorFio(fio, date, date).stream()
                .filter(e -> e.time().equals(time))
                .findFirst();
    }

    @Override
    public Health health() {
        try {
            return new Health(true, name(), doctors.count() > 0 ? 1 : 0, null);
        } catch (RuntimeException e) {
            return new Health(false, name(), 0, e.getMessage());
        }
    }

    /** Разворачивает «время врача + дни недели» в список приёмов в окне дат. */
    private List<ScheduleEntry> expand(Doctor d, LocalDate from, LocalDate to) {
        List<ScheduleEntry> result = new ArrayList<>();
        if (from == null || to == null || to.isBefore(from)) {
            return result;
        }
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (d.worksOn(date)) {
                result.add(new ScheduleEntry(d.getId(), d.getFio(), d.getSpecialty(),
                        date, d.getTime(), null));
            }
        }
        return result;
    }
}