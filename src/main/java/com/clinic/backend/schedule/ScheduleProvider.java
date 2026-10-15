package com.clinic.backend.schedule;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * ПОР расписания. Единственное место, где известно, откуда берутся слоты.
 *
 * Источник выбирается флагом {@code app.schedule.source} (db | excel) и
 * подменяется целиком: реализация в бин, остальные этапы не знают, что
 * источник поменялся. Чтобы добавить МИС или ЕПГУ — новая реализация этого
 * интерфейса, больше ни одной правки.
 *
 * Реализации обязаны быть ТОЛЬКО ДЛЯ ЧТЕНИЯ: запись в БД идёт отдельно,
 * чтобы нельзя было «случайно» создать приём из чужого источника.
 */
public interface ScheduleProvider {

    /** Человекочитаемое имя источника — попадает в /api/voice/parser и в логи. */
    String name();

    /** Все приёмы по специальности в окне дат [from, to] включительно. */
    List<ScheduleEntry> bySpecialty(String specialty, LocalDate from, LocalDate to);

    /** Все приёмы в окне дат — нужно, когда специальность ещё не названа. */
    List<ScheduleEntry> all(LocalDate from, LocalDate to);

    /** Приёмы конкретного врача. */
    List<ScheduleEntry> byDoctorFio(String fio, LocalDate from, LocalDate to);

    /**
     * Слот конкретного врача в конкретное время.
     * Нужен для проверки «а есть ли такой приём вообще» до предложения пациенту.
     */
    Optional<ScheduleEntry> exact(String fio, LocalDate date, String time);

    /** Диагностика источника: удалось ли прочитать, сколько строк. */
    default Health health() {
        return new Health(true, name(), 0, null);
    }

    /**
     * Состояние источника.
     *
     * @param readable признак, что источник вообще читается (у Excel это «файл есть и парсится»)
     * @param source   имя источника
     * @param entries  число строк в окне по умолчанию
     * @param error    текст проблемы, если readable=false
     */
    record Health(boolean readable, String source, int entries, String error) {
    }
}