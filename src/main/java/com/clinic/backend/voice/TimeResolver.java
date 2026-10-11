package com.clinic.backend.voice;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Преобразует абстрактные факторы времени модели (T_TOMORROW, T_FRI, T_MORNING,
 * T_H14_00, ...) в конкретную дату и временное окно. Вся «математика времени»
 * изолирована здесь — остальной диалог работает с уже разрешённым окном.
 */
public final class TimeResolver {

    private TimeResolver() {
    }

    /** Конкретная дата (может быть null = «как можно раньше») и окно времени. */
    public record TimePreference(LocalDate date, String earliest, String latest,
                                 String exact, boolean soon, String label) {

        public boolean matches(String hhmm) {
            LocalTime t = LocalTime.parse(hhmm);
            if (exact != null) {
                return hhmm.equals(exact);
            }
            if (earliest != null && t.isBefore(LocalTime.parse(earliest))) {
                return false;
            }
            if (latest != null && t.isAfter(LocalTime.parse(latest))) {
                return false;
            }
            return true;
        }
    }

    public static TimePreference resolve(List<String> factors, LocalDate today) {
        LocalDate date = null;
        String earliest = null;
        String latest = null;
        String exact = null;
        boolean soon = false;

        for (String f : factors) {
            switch (f) {
                case "T_TODAY" -> date = today;
                case "T_TOMORROW" -> date = today.plusDays(1);
                case "T_DAY_AFTER_TOMORROW", "T_IN_2_DAYS" -> date = today.plusDays(2);
                case "T_IN_3_DAYS" -> date = today.plusDays(3);
                case "T_IN_WEEK", "T_NEXT_WEEK" -> date = today.plusDays(7);
                case "T_IN_2_WEEKS" -> date = today.plusDays(14);
                case "T_IN_MONTH", "T_NEXT_MONTH" -> date = today.plusDays(30);
                case "T_MON" -> date = nextWeekday(today, DayOfWeek.MONDAY);
                case "T_TUE" -> date = nextWeekday(today, DayOfWeek.TUESDAY);
                case "T_WED" -> date = nextWeekday(today, DayOfWeek.WEDNESDAY);
                case "T_THU" -> date = nextWeekday(today, DayOfWeek.THURSDAY);
                case "T_FRI" -> date = nextWeekday(today, DayOfWeek.FRIDAY);
                case "T_SAT" -> date = nextWeekday(today, DayOfWeek.SATURDAY);
                case "T_SUN" -> date = nextWeekday(today, DayOfWeek.SUNDAY);
                case "T_WEEKEND" -> date = nextWeekday(today, DayOfWeek.SATURDAY);
                default -> {
                    if (f.startsWith("T_H")) {
                        exact = f.replace("T_H", "").replace("_", ":");
                    } else {
                        switch (f) {
                            case "T_MORNING", "T_EARLY", "T_BEFORE_NOON" -> latest = "11:59";
                            case "T_MIDDAY", "T_AFTERNOON", "T_AFTER_NOON" -> earliest = "12:00";
                            case "T_EVENING", "T_AFTER_WORK", "T_AFTER_SHIFT" -> earliest = "16:00";
                            case "T_AFTER_18" -> earliest = "18:00";
                            case "T_SOON" -> soon = true;
                            default -> {
                                // неизвестный фактор игнорируем
                            }
                        }
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        if (date != null) {
            sb.append(date);
        } else {
            sb.append("ближайшее время");
        }
        if (exact != null) {
            sb.append(" в ").append(exact);
        } else if (earliest != null && latest != null) {
            sb.append(", ").append(earliest).append('-').append(latest);
        } else if (earliest != null) {
            sb.append(", после ").append(earliest);
        } else if (latest != null) {
            sb.append(", до ").append(latest);
        }
        return new TimePreference(date, earliest, latest, exact, soon, sb.toString());
    }

    private static LocalDate nextWeekday(LocalDate from, DayOfWeek target) {
        LocalDate d = from;
        for (int i = 0; i < 7; i++) {
            if (d.getDayOfWeek() == target) {
                return d;
            }
            d = d.plusDays(1);
        }
        return from;
    }
}
