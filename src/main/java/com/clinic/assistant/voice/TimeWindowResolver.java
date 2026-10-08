package com.clinic.assistant.voice;

import com.clinic.assistant.config.VoiceProperties;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Переводит факторы времени модели ({@code T_TOMORROW}, {@code T_MORNING},
 * {@code T_H10_00}, …) в конкретное окно поиска слотов.
 *
 * <p>Токены и их семантика — из модели парсера (models/ANDROID_INTEGRATION.md):
 * факторы идут категориями «часть дня → день → время», поэтому обрабатываем их
 * независимо и объединяем пересечением.</p>
 */
@Component
public class TimeWindowResolver {

    private static final Pattern CLOCK = Pattern.compile("T_H(\\d{2})_(\\d{2})");

    /** T_MON … T_SUN → день недели. */
    private static final Map<String, DayOfWeek> WEEKDAY_TOKENS = Map.of(
            "MON", DayOfWeek.MONDAY, "TUE", DayOfWeek.TUESDAY, "WED", DayOfWeek.WEDNESDAY,
            "THU", DayOfWeek.THURSDAY, "FRI", DayOfWeek.FRIDAY, "SAT", DayOfWeek.SATURDAY,
            "SUN", DayOfWeek.SUNDAY);

    private final int searchDaysAhead;

    public TimeWindowResolver(VoiceProperties properties) {
        this.searchDaysAhead = properties.getSearchDaysAhead();
    }

    public TimeWindow resolve(List<String> factors, LocalDate today) {
        LocalDate from = today;
        LocalDate to = today.plusDays(searchDaysAhead);
        LocalTime earliest = LocalTime.of(0, 0);
        LocalTime latest = LocalTime.of(23, 59);

        if (factors == null || factors.isEmpty() || factors.contains("T_UNKNOWN")) {
            return new TimeWindow(from, to, earliest, latest);
        }

        for (String raw : factors) {
            String f = raw.toUpperCase(Locale.ROOT);

            // ---------- конкретный день / период ----------
            LocalDate dayFrom = null;
            LocalDate dayTo = null;
            switch (f) {
                case "T_TODAY" -> dayFrom = dayTo = today;
                case "T_TOMORROW" -> dayFrom = dayTo = today.plusDays(1);
                case "T_DAY_AFTER_TOMORROW", "T_IN_2_DAYS" -> dayFrom = dayTo = today.plusDays(2);
                case "T_IN_3_DAYS" -> dayFrom = dayTo = today.plusDays(3);
                case "T_SOON" -> dayFrom = dayTo = today.plusDays(3);
                case "T_MON", "T_TUE", "T_WED", "T_THU", "T_FRI", "T_SAT", "T_SUN" -> {
                    DayOfWeek target = WEEKDAY_TOKENS.get(f.substring(2));
                    LocalDate next = today.with(TemporalAdjusters.next(target));
                    // если сегодня тот же день — считаем «в этот же день», если он ещё впереди
                    if (today.getDayOfWeek() == target) {
                        next = today;
                    }
                    dayFrom = dayTo = next;
                }
                case "T_WEEKEND" -> {
                    LocalDate saturday = today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY));
                    if (today.getDayOfWeek() == DayOfWeek.SATURDAY
                            || today.getDayOfWeek() == DayOfWeek.SUNDAY) {
                        saturday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY));
                    }
                    dayFrom = saturday;
                    dayTo = saturday.plusDays(1);
                }
                case "T_THIS_WEEK" -> dayFrom = today;
                case "T_NEXT_WEEK" -> {
                    LocalDate start = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
                    dayFrom = start;
                    dayTo = start.plusDays(6);
                }
                case "T_IN_WEEK" -> dayTo = today.plusDays(7);
                case "T_IN_2_WEEKS" -> dayTo = today.plusDays(14);
                case "T_THIS_MONTH" -> dayTo = today.with(TemporalAdjusters.lastDayOfMonth());
                case "T_NEXT_MONTH" -> {
                    LocalDate start = today.with(TemporalAdjusters.firstDayOfNextMonth());
                    dayFrom = start;
                    dayTo = start.with(TemporalAdjusters.lastDayOfMonth());
                }
                case "T_IN_MONTH" -> dayTo = today.plusDays(30);
                default -> {
                }
            }
            if (dayFrom != null && dayFrom.isAfter(from)) {
                from = dayFrom;
            }
            if (dayTo != null && dayTo.isBefore(to)) {
                to = dayTo;
            }
            if (dayFrom != null && dayTo != null) {
                // жёстко заданный день (отдельно от «периодов») сужает и from, и to
                if (isSpecificDayFactor(f)) {
                    from = dayFrom;
                    to = dayTo;
                }
            }

            // ---------- часть дня ----------
            LocalTime[] part = partOfDay(f);
            if (part != null) {
                if (part[0].isAfter(earliest)) {
                    earliest = part[0];
                }
                if (part[1].isBefore(latest)) {
                    latest = part[1];
                }
            }

            // ---------- конкретные часы ----------
            var clock = CLOCK.matcher(f);
            if (clock.matches()) {
                LocalTime exact = LocalTime.of(
                        Integer.parseInt(clock.group(1)),
                        Integer.parseInt(clock.group(2)));
                if (!exact.isBefore(earliest) && !exact.isAfter(latest)) {
                    earliest = exact;
                    latest = exact;
                } else if (earliest.equals(LocalTime.MIDNIGHT) && latest.equals(LocalTime.of(23, 59))) {
                    earliest = exact;
                    latest = exact;
                }
            }
        }
        if (to.isBefore(from)) {
            from = to;
        }
        return new TimeWindow(from, to, earliest, latest);
    }

    private static boolean isSpecificDayFactor(String f) {
        return switch (f) {
            case "T_TODAY", "T_TOMORROW", "T_DAY_AFTER_TOMORROW", "T_IN_2_DAYS", "T_IN_3_DAYS",
                 "T_MON", "T_TUE", "T_WED", "T_THU", "T_FRI", "T_SAT", "T_SUN",
                 "T_NEXT_WEEK", "T_NEXT_MONTH" -> true;
            default -> false;
        };
    }

    /** Часть дня → [начало, конец]. */
    private static LocalTime[] partOfDay(String f) {
        return switch (f) {
            case "T_EARLY" -> times(8, 0, 10, 0);
            case "T_MORNING", "T_BEFORE_NOON" -> times(8, 0, 12, 0);
            case "T_MIDDAY" -> times(11, 0, 14, 0);
            case "T_AFTERNOON", "T_AFTER_NOON" -> times(12, 0, 17, 0);
            case "T_AFTER_SHIFT" -> times(14, 0, 19, 0);
            case "T_AFTER_WORK" -> times(17, 0, 21, 0);
            case "T_EVENING" -> times(17, 0, 22, 0);
            case "T_AFTER_18" -> times(18, 0, 23, 59);
            default -> null;
        };
    }

    private static LocalTime[] times(int h1, int m1, int h2, int m2) {
        return new LocalTime[]{LocalTime.of(h1, m1), LocalTime.of(h2, m2)};
    }
}
