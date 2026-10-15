package com.clinic.backend.voice;

import com.clinic.backend.rag.ClinicalKnowledgeBase;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Преобразует сказанное пациентом в конкретную дату и окно времени.
 *
 * Источники сведений о времени, по убыванию приоритета:
 *   1. текст реплики целиком — «на 16 октября», «в четыре часа дня»;
 *   2. токены времени модели — T_TOMORROW, T_FRI, T_H09_45, T_AFTER_NOON...
 *   3. ничего не сказано -> «как можно раньше».
 *
 * Текст разбирается раньше токенов сознательно: модель не умеет абсолютные
 * даты и почти не умеет словесное время («в четыре часа дня»), а пациент
 * говорит именно так. Модель при этом остаётся полезной там, где текст
 * разбирать нечего: на «послезавтра», «в пятницу», «после обеда».
 */
public final class TimeResolver {

    private TimeResolver() {
    }

    /**
     * Желаемое окно приёма.
     *
     * @param date        конкретная дата; null = «как можно раньше»
     * @param exact       точное время HH:mm; null = окно не задано
     * @param earliest    начало окна, HH:mm
     * @param latest      конец окна, HH:mm
     * @param excludeDay  день недели, который пациент отверг («лучше не в пятницу»)
     * @param soon        «как можно скорее» — берём самый ближний слот
     * @param label       человекочитаемое описание пожелания для подтверждения
     */
    public record TimePreference(LocalDate date, String exact, String earliest, String latest,
                                 DayOfWeek excludeDay, boolean soon, String label) {

        /** Подходит ли время приёма врача под это пожелание. */
        public boolean matches(LocalDate slotDate, String hhmm) {
            if (excludeDay != null && slotDate != null && slotDate.getDayOfWeek() == excludeDay) {
                return false;
            }
            if (slotDate != null && date != null && !slotDate.equals(date)) {
                return false;
            }
            if (exact != null) {
                return hhmm != null && hhmm.equals(exact);
            }
            if (hhmm == null) {
                return true;
            }
            LocalTime t = RussianTime.parseHhmm(hhmm);
            if (t == null) {
                return true;
            }
            if (earliest != null && t.isBefore(LocalTime.parse(earliest))) {
                return false;
            }
            if (latest != null && t.isAfter(LocalTime.parse(latest))) {
                return false;
            }
            return true;
        }

        /** Ничего конкретного не сказали. */
        public boolean isEmpty() {
            return date == null && exact == null && earliest == null
                    && latest == null && excludeDay == null && !soon;
        }
    }

    /** Разбирает реплику пациента. */
    public static TimePreference resolve(List<String> modelFactors, String rawText, LocalDate today) {
        TextHints hints = readText(rawText, today);

        LocalDate date = hints.date;
        String exact = hints.exact;
        String earliest = hints.earliest;
        String latest = hints.latest;
        DayOfWeek excludeDay = hints.excludeDay;
        boolean soon = hints.soon;

        for (String f : modelFactors == null ? List.<String>of() : modelFactors) {
            switch (f) {
                case "T_TODAY" -> { if (date == null) date = today; }
                case "T_TOMORROW" -> { if (date == null) date = today.plusDays(1); }
                case "T_DAY_AFTER_TOMORROW", "T_IN_2_DAYS" -> {
                    if (date == null) date = today.plusDays(2);
                }
                case "T_IN_3_DAYS" -> { if (date == null) date = today.plusDays(3); }
                case "T_IN_WEEK", "T_NEXT_WEEK" -> { if (date == null) date = today.plusDays(7); }
                case "T_IN_2_WEEKS", "T_NEXT_MONTH" -> { if (date == null) date = today.plusDays(14); }
                case "T_IN_MONTH" -> { if (date == null) date = today.plusDays(30); }
                case "T_MON" -> { if (date == null) date = RussianTime.nextWeekday(today, DayOfWeek.MONDAY); }
                case "T_TUE" -> { if (date == null) date = RussianTime.nextWeekday(today, DayOfWeek.TUESDAY); }
                case "T_WED" -> { if (date == null) date = RussianTime.nextWeekday(today, DayOfWeek.WEDNESDAY); }
                case "T_THU" -> { if (date == null) date = RussianTime.nextWeekday(today, DayOfWeek.THURSDAY); }
                case "T_FRI" -> { if (date == null) date = RussianTime.nextWeekday(today, DayOfWeek.FRIDAY); }
                case "T_SAT" -> { if (date == null) date = RussianTime.nextWeekday(today, DayOfWeek.SATURDAY); }
                case "T_SUN" -> { if (date == null) date = RussianTime.nextWeekday(today, DayOfWeek.SUNDAY); }
                // «в четыре часа дня» модель в число не переводит — берём из текста,
                // но если модель назвала точное время, оно приоритетнее
                default -> {
                    if (f.startsWith("T_H") && exact == null) {
                        String hhmm = f.substring(3).replace('_', ':');
                        if (RussianTime.parseHhmm(hhmm) != null) {
                            exact = hhmm;
                        }
                    }
                }
            }
        }

        String[] window = RussianTime.partOfDayWindow(
                hints.part != null ? hints.part : partFromModel(modelFactors));
        if (window != null && exact == null) {
            earliest = window[0];
            latest = window[1];
        }

        return new TimePreference(date, exact, earliest, latest, excludeDay, soon, label(date, exact, earliest, latest, excludeDay));
    }

    /** Старый вызов без текста — остаётся рабочим для вызывающего кода. */
    public static TimePreference resolve(List<String> modelFactors, LocalDate today) {
        return resolve(modelFactors, null, today);
    }

    private static String partFromModel(List<String> factors) {
        if (factors == null) {
            return null;
        }
        for (String f : factors) {
            String p = switch (f) {
                case "T_MORNING", "T_EARLY", "T_BEFORE_NOON" -> "morning";
                case "T_MIDDAY", "T_AFTERNOON", "T_AFTER_NOON" -> "afternoon";
                case "T_EVENING", "T_AFTER_WORK", "T_AFTER_SHIFT", "T_AFTER_18" -> "evening";
                case "T_SOON" -> null;
                default -> null;
            };
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Разбор текста
    // ------------------------------------------------------------------

    private record TextHints(LocalDate date, String exact, String earliest, String latest,
                             DayOfWeek excludeDay, boolean soon, String part) {
    }

    private static TextHints readText(String text, LocalDate today) {
        if (text == null || text.isBlank()) {
            return new TextHints(null, null, null, null, null, false, null);
        }
        String q = " " + ClinicalKnowledgeBase.normalize(text).replaceAll("[^\\p{L}\\p{N}\\s:.-]", " ") + " ";
        LocalDate date = null;
        String exact = null;
        DayOfWeek excludeDay = null;
        boolean soon = false;
        String part = RussianTime.partOfDay(q);

        // «лучше не в пятницу» / «только не в пятницу» — день, который отвергают
        String negated = q.replaceAll("(не|только не|лучше не|кроме)\\s+в\\s+", "!в ");
        DayOfWeek ex = weekdayOf(negated, "!в ");
        if (ex != null) {
            excludeDay = ex;
        }

        // цифровое время «в 15:00», «в 9.45»
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{1,2})[:.](\\d{2})").matcher(q);
        if (m.find()) {
            LocalTime t = LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            exact = t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        }

        // словесное время «в четыре часа дня», «в девять тридцать»
        if (exact == null) {
            LocalTime t = RussianTime.parseWordsTime(q);
            if (t != null) {
                exact = t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            }
        }

        // относительные даты
        if (q.contains("послезавтра")) {
            date = today.plusDays(2);
        } else if (q.contains("завтра")) {
            date = today.plusDays(1);
        } else if (q.contains("сегодня")) {
            date = today;
        }

        // «через неделю», «через три дня»
        if (date == null) {
            java.util.regex.Matcher w = java.util.regex.Pattern
                    .compile("через\\s+(\\S+)\\s+(день|дня|дней|недел|недели|месяц)").matcher(q);
            if (w.find()) {
                int n = RussianTime.parseNumber(w.group(1));
                String unit = w.group(2);
                if (n > 0) {
                    if (unit.startsWith("недел")) {
                        date = today.plusWeeks(n);
                    } else if (unit.startsWith("месяц")) {
                        date = today.plusMonths(n);
                    } else {
                        date = today.plusDays(n);
                    }
                }
            }
        }

        // абсолютная дата словами: «первого октября», «16 октября»
        if (date == null) {
            date = RussianTime.parseDayMonth(q, today);
        }

        // цифровая дата: «15.10.2026», «15.10», «2026-10-15»
        if (date == null) {
            java.util.regex.Matcher d = java.util.regex.Pattern
                    .compile("(\\d{4})-(\\d{2})-(\\d{2})|(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{2,4}))?")
                    .matcher(q);
            if (d.find()) {
                try {
                    if (d.group(1) != null) {
                        date = LocalDate.of(Integer.parseInt(d.group(1)),
                                Integer.parseInt(d.group(2)), Integer.parseInt(d.group(3)));
                    } else {
                        int day = Integer.parseInt(d.group(4));
                        int month = Integer.parseInt(d.group(5));
                        int year = today.getYear();
                        if (d.group(6) != null) {
                            int y = Integer.parseInt(d.group(6));
                            year = y < 100 ? (y > 30 ? 1900 + y : 2000 + y) : y;
                        } else if (LocalDate.of(year, month, day).isBefore(today.minusMonths(6))) {
                            year = year + 1;
                        }
                        date = LocalDate.of(year, month, day);
                    }
                } catch (Exception ignored) {
                    // не дата
                }
            }
        }

        // день недели без отрицания
        if (date == null) {
            DayOfWeek wd = weekdayOf(q, "в ");
            if (wd != null) {
                date = RussianTime.nextWeekday(today, wd);
            }
        }

        if (q.contains("как можно") || q.contains("скорее") || q.contains("ближе")
                || q.contains("пораньше")) {
            soon = true;
        }
        if (q.contains("на следующей неделе") && date == null) {
            date = today.plusWeeks(1);
        }
        return new TextHints(date, exact, null, null, excludeDay, soon, part);
    }

    private static DayOfWeek weekdayOf(String text, String prefix) {
        for (DayOfWeek d : DayOfWeek.values()) {
            String nominative = switch (d) {
                case MONDAY -> "понедельник";
                case TUESDAY -> "вторник";
                case WEDNESDAY -> "сред";
                case THURSDAY -> "четверг";
                case FRIDAY -> "пятниц";
                case SATURDAY -> "суббот";
                case SUNDAY -> "воскресень";
            };
            if (text.contains(prefix + nominative)) {
                return d;
            }
        }
        return null;
    }

    /** Описание пожелания словами — для подтверждения пациенту. */
    private static String label(LocalDate date, String exact, String earliest, String latest,
                                DayOfWeek excludeDay) {
        StringBuilder sb = new StringBuilder();
        if (date != null) {
            sb.append(RussianTime.sayDate(date));
        }
        if (exact != null) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(RussianTime.sayTime(exact));
        } else if (earliest != null || latest != null) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(earliest != null && latest != null ? earliest + "–" + latest
                    : latest != null ? "до " + latest : "после " + earliest);
        }
        if (excludeDay != null) {
            sb.append(sb.length() > 0 ? ", кроме " : "кроме ").append(
                    RussianTime.weekdayGenitive(LocalDate.now()
                            .with(java.time.temporal.TemporalAdjusters.nextOrSame(excludeDay))));
        }
        return sb.length() == 0 ? "ближайшее время" : sb.toString();
    }
}