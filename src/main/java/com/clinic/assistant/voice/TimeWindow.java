package com.clinic.assistant.voice;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Окно дат/времён, в котором конвейер ищет свободные слоты.
 *
 * <p>Получается из факторов времени фрейма модели
 * (T_TOMORROW, T_MORNING, T_H10_00, …).</p>
 */
public record TimeWindow(LocalDate from, LocalDate to, LocalTime earliest, LocalTime latest) {

    public boolean matches(LocalDate date, LocalTime time) {
        return !date.isBefore(from)
                && !date.isAfter(to)
                && !time.isBefore(earliest)
                && !time.isAfter(latest);
    }

    public String describe() {
        return from + " — " + to + ", " + earliest + "–" + latest;
    }
}
