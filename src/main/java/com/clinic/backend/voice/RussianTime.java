package com.clinic.backend.voice;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Русские даты и время: разбор реплики и обратная озвучка для синтеза речи.
 *
 * ЗАЧЕМ ОДНО И ТО ЖЕ КЛАСС В ОБЕ СТОРОНЫ. Регламент требует называть даты
 * словами с днём недели («в четверг, первого октября») и время словами
 * («в девять тридцать»), а модель и пациент говорят цифрами и сокращениями.
 * Если разбор и озвучка живут в разных местах, они рано или поздно
 * разъезжаются. Здесь обе операции опираются на одни и те же таблицы.
 *
 * Класс статический и без Spring: его можно вызывать и из сценария, и из тестов.
 */
public final class RussianTime {

    private RussianTime() {
    }

    // ------------------------------------------------------------------
    // Числа прописью — используются и для времени («девять тридцать»),
    // и для порядковых числительных даты («первого октября»).
    // ------------------------------------------------------------------

    private static final String[] ONES_M = {
            "ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
    private static final String[] ONES_F = {
            "ноль", "одна", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
    private static final String[] TEENS = {
            "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать",
            "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"};
    private static final String[] TENS = {
            "", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят",
            "семьдесят", "восемьдесят", "девяносто"};

    /** Прописью число в мужском роде — для часов («в девять», «в двадцать один»). */
    public static String number(int n) {
        if (n < 0) {
            return "";
        }
        if (n >= 100) {
            return hundreds(n);
        }
        if (n >= 20) {
            int t = n / 10, o = n % 10;
            return o == 0 ? TENS[t] : TENS[t] + " " + ONES_M[o];
        }
        if (n >= 10) {
            return TEENS[n - 10];
        }
        return ONES_M[n];
    }

    /** Прописью число в женском роде — для минут («тридцать», «двадцать одна»). */
    public static String numberF(int n) {
        if (n >= 20 && n % 10 != 0) {
            int t = n / 10, o = n % 10;
            return TENS[t] + " " + ONES_F[o];
        }
        return number(n);
    }

    private static String hundreds(int n) {
        int h = n / 100, r = n % 100;
        String head = switch (h) {
            case 1 -> "сто";
            case 2 -> "двести";
            case 3 -> "триста";
            case 4 -> "четыреста";
            case 5 -> "пятьсот";
            case 6 -> "шестьсот";
            case 7 -> "семьсот";
            case 8 -> "восемьсот";
            case 9 -> "девятьсот";
            default -> "";
        };
        return r == 0 ? head : head + " " + number(r);
    }

    // ------------------------------------------------------------------
    // Озвучка времени
    // ------------------------------------------------------------------

    /** «09:30» -> «в девять тридцать». Час всегда с «в», минуты — без предлога. */
    public static String sayTime(String hhmm) {
        LocalTime t = parseHhmm(hhmm);
        if (t == null) {
            return hhmm == null ? "" : hhmm;
        }
        return sayTime(t);
    }

    public static String sayTime(LocalTime t) {
        StringBuilder sb = new StringBuilder("в ");
        if (t.getHour() == 0 && t.getMinute() == 0) {
            return "в полдень";
        }
        sb.append(number(t.getHour()));
        if (t.getMinute() != 0) {
            sb.append(' ').append(numberF(t.getMinute()));
        }
        return sb.toString();
    }

    /** «08:45» -> «в восемь сорок пять» — с дефисом на минуты, так понятнее на слух. */
    public static String sayTimeWithMinutes(String hhmm) {
        LocalTime t = parseHhmm(hhmm);
        if (t == null) {
            return hhmm == null ? "" : hhmm;
        }
        if (t.getMinute() == 0) {
            return sayTime(t);
        }
        return "в " + number(t.getHour()) + "-" + numberF(t.getMinute());
    }

    // ------------------------------------------------------------------
    // Озвучка даты
    // ------------------------------------------------------------------

    private static final String[] WEEKDAYS_NOM = {
            "понедельник", "вторник", "среда", "четверг",
            "пятница", "суббота", "воскресенье"};

    /**
     * Дни недели в винительном падеже — для «в пятницу», «в среду».
     * В именительном выходило «в пятница»: по-русски так не говорят,
     * и синтез звучит неграмотно.
     */
    private static final String[] WEEKDAYS_ACC = {
            "понедельник", "вторник", "среду", "четверг",
            "пятницу", "субботу", "воскресенье"};

    /**
     * Родительный падеж для «кроме понедельника», «после среды».
     * В именительном выходило «кроме понедельник» — синтез такое читает
     * как «кроме понедельник-а», пациент не понимает, какой день исключён.
     */
    private static final String[] WEEKDAYS_GEN = {
            "понедельника", "вторника", "среды", "четверга",
            "пятницы", "субботы", "воскресенья"};
    private static final String[] MONTHS_GEN = {
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря"};

    /** «в четверг, первого октября» — как требует регламент (§3.2). */
    public static String sayDate(LocalDate date) {
        if (date == null) {
            return "";
        }
        return "в " + WEEKDAYS_ACC[date.getDayOfWeek().getValue() - 1]
                + ", " + sayDayOfMonth(date.getDayOfMonth()) + " " + MONTHS_GEN[date.getMonthValue() - 1];
    }

    /** «первого октября» без дня недели. */
    public static String sayDayAndMonth(LocalDate date) {
        if (date == null) {
            return "";
        }
        return sayDayOfMonth(date.getDayOfMonth()) + " " + MONTHS_GEN[date.getMonthValue() - 1];
    }

    public static String weekday(LocalDate date) {
        return date == null ? "" : WEEKDAYS_NOM[date.getDayOfWeek().getValue() - 1];
    }

    /** «пятницу» — винительный падеж, для «в пятницу в девять тридцать». */
    public static String weekdayAccusative(LocalDate date) {
        return date == null ? "" : WEEKDAYS_ACC[date.getDayOfWeek().getValue() - 1];
    }

    /** «понедельника» — родительный падеж, для «кроме понедельника». */
    public static String weekdayGenitive(LocalDate date) {
        return date == null ? "" : WEEKDAYS_GEN[date.getDayOfWeek().getValue() - 1];
    }

    /** Порядковое числительное в родительном: 1 -> первого, 5 -> пятого, 30 -> тридцатого. */
    public static String sayDayOfMonth(int day) {
        return ordinalGenitive(day);
    }

    /**
     * Родительный падеж порядкового числительного. Именно в этом падеже стоит
     * дата в речи: «первого октября», «пятого марта».
     */
    private static String ordinalGenitive(int n) {
        int lastTwo = n % 100;
        if ((n >= 11 && n <= 14) || (lastTwo >= 11 && lastTwo <= 14)) {
            return number(n) + "ого";
        }
        if (n < 20) {
            return switch (n) {
                case 1 -> "первого";
                case 2 -> "второго";
                case 3 -> "третьего";
                case 4 -> "четвертого";
                case 5 -> "пятого";
                case 6 -> "шестого";
                case 7 -> "седьмого";
                case 8 -> "восьмого";
                case 9 -> "девятого";
                // одиннадцать -> одиннадцатого, пятнадцать -> пятнадцатого:
                // мягкий знак отбрасывается, добавляется «ого»
                default -> stripSoft(number(n)) + "ого";
            };
        }
        int tens = n / 10, unit = n % 10;
        if (unit == 0) {
            return TENS[tens] + "ого";
        }
        return TENS[tens] + " " + switch (unit) {
            case 1 -> "первого";
            case 2 -> "второго";
            case 3 -> "третьего";
            case 4 -> "четвертого";
            case 5 -> "пятого";
            case 6 -> "шестого";
            case 7 -> "седьмого";
            case 8 -> "восьмого";
            case 9 -> "девятого";
            default -> "";
        };
    }

    /** «пятнадцать» -> «пятнадцат»: мягкий знак перед -ого/-го не читается. */
    private static String stripSoft(String word) {
        return word.endsWith("ь") ? word.substring(0, word.length() - 1) : word;
    }

    /**
     * Порядковое числительное прописью, как пациент говорит дату вслух:
     * «пятнадцатого», «первого», «тридцатого».
     */
    public static int parseOrdinal(String word) {
        if (word == null) {
            return -1;
        }
        String w = stripPunctuation(word.trim().toLowerCase(Locale.ROOT).replace('ё', 'е'));
        if (w.isEmpty()) {
            return -1;
        }
        for (Map.Entry<String, Integer> e : ORDINAL_WORDS.entrySet()) {
            if (w.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return -1;
    }

    private static final Map<String, Integer> ORDINAL_WORDS = Map.ofEntries(
            Map.entry("перв", 1), Map.entry("втор", 2), Map.entry("трет", 3),
            Map.entry("четверт", 4), Map.entry("пят", 5),
            Map.entry("шест", 6), Map.entry("седьм", 7), Map.entry("восьм", 8),
            Map.entry("девят", 9), Map.entry("десят", 10), Map.entry("одиннадцат", 11),
            Map.entry("двенадцат", 12), Map.entry("тринадцат", 13), Map.entry("четырнадцат", 14),
            Map.entry("пятнадцат", 15), Map.entry("шестнадцат", 16), Map.entry("семнадцат", 17),
            Map.entry("восемнадцат", 18), Map.entry("девятнадцат", 19), Map.entry("двадцат", 20),
            Map.entry("тридцат", 30));

    /** Дата и время одной фразой: «в четверг, первого октября, в девять тридцать». */
    public static String sayDateTime(LocalDate date, String hhmm) {
        return sayDate(date) + ", " + sayTime(hhmm);
    }

    // ------------------------------------------------------------------
    // Разбор того, что сказал пациент
    // ------------------------------------------------------------------

    /** «09:30», «9.30», «9:30:00», «0930». */
    public static LocalTime parseHhmm(String s) {
        if (s == null) {
            return null;
        }
        String q = s.trim();
        try {
            return LocalTime.parse(q.length() == 4 && q.chars().allMatch(Character::isDigit)
                    ? q.substring(0, 2) + ":" + q.substring(2) : q);
        } catch (Exception ignored) {
            // пробуем другие разделители
        }
        String[] parts = q.replace('.', ':').replace(' ', ':').split(":");
        if (parts.length == 2) {
            try {
                return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Число прописью -> число. Понимает до 100 и составные («двадцать один»).
     * Возвращает -1, если разобрать не удалось.
     */
    public static int parseNumber(String words) {
        if (words == null) {
            return -1;
        }
        String q = words.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (q.isEmpty()) {
            return -1;
        }
        // цифрами
        if (q.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(q);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        int total = 0;
        boolean any = false;
        for (String part : q.split("[\\s-]+")) {
            if (part.isEmpty()) {
                continue;
            }
            int v = lookupWord(part);
            if (v < 0) {
                // «девятнадцатого» / «часа» — суффиксы порядковых и падежей
                v = lookupWord(stripSuffix(part));
            }
            if (v < 0) {
                // «пятнадцатого» — порядковое числительное даты
                v = parseOrdinal(part);
            }
            if (v < 0) {
                return -1;
            }
            total += v;
            any = true;
        }
        return any ? total : -1;
    }

    private static String stripSuffix(String w) {
        for (String s : new String[]{"ого", "его", "ому", "ему", "ыми", "ими", "ой", "ей", "ый", "ий"}) {
            if (w.length() > s.length() + 2 && w.endsWith(s)) {
                return w.substring(0, w.length() - s.length());
            }
        }
        return w;
    }

    private static int lookupWord(String w) {
        if (w.isEmpty()) {
            return -1;
        }
        // составные: двадцать, тридцать, ... отдельно, далее единицы
        for (int t = 9; t >= 2; t--) {
            if (w.equals(TENS[t])) {
                return t * 10;
            }
        }
        for (int i = 0; i < TEENS.length; i++) {
            if (w.equals(TEENS[i])) {
                return 10 + i;
            }
        }
        for (int i = 0; i < ONES_M.length; i++) {
            if (w.equals(ONES_M[i]) || w.equals(ONES_F[i])) {
                return i;
            }
        }
        return -1;
    }

    /**
     * «в четыре часа дня» -> 16:00, «в девять тридцать» -> 09:30,
     * «в девять пятнадцать» -> 09:15.
     *
     * Разбор словами нужен потому, что пациент говорит именно так, а модель
     * такие фразы в числа не переводит: «в четыре часа дня» — обычный запрос.
     */
    public static LocalTime parseWordsTime(String text) {
        if (text == null) {
            return null;
        }
        String q = " " + text.toLowerCase(Locale.ROOT).replace('ё', 'е').trim() + " ";
        // «после семи вечера» — «семи» к семнадцати
        if (q.contains("вечер") || q.contains("вечера")) {
            q = q.replace("семи", "семнадцати").replace("семи часов", "семнадцати");
        }
        q = q.replace("полдень", "12:00");

        String[] tokens = q.split("\\s+");
        Integer hour = null;
        Integer minute = null;
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.equals("часов") || t.equals("час") || t.equals("часа")) {
                continue;
            }
            // «четыре часа дня» и «пять вечера» — оба означают вторая половина суток
            if ((t.equals("дня") || t.equals("вечера") || t.equals("ночи")) && hour != null) {
                hour = (hour + 12) % 24;
                continue;
            }
            if (t.equals("утра")) {
                continue;
            }
            if (isMonthToken(t)) {
                continue;   // «на 16 октября» — число 16 не час
            }
            int v = parseNumber(stripPunctuation(t));
            if (v < 0) {
                continue;
            }
            // В тексте с датой вида «на 16 октября» голое число относится к дате.
            // Без этой проверки «на 16 октября в четыре часа дня» читалось как 04:04.
            boolean dateContext = t.chars().allMatch(Character::isDigit) && mentionsMonth(q);
            if (dateContext) {
                continue;
            }
            if (hour == null) {
                hour = v;
            } else if (minute == null && v < 60) {
                minute = v;
            }
        }
        if (hour == null) {
            return null;
        }
        hour = hour % 24;
        if (minute == null) {
            minute = 0;
        }
        try {
            return LocalTime.of(hour, minute);
        } catch (Exception e) {
            return null;
        }
    }

    /** Назван ли месяц — тогда числа рядом относятся к дате. */
    private static boolean mentionsMonth(String q) {
        for (String m : MONTHS_GEN) {
            if (q.contains(m)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMonthToken(String t) {
        String w = stripPunctuation(t);
        for (String m : MONTHS_GEN) {
            if (w.length() >= 4 && m.startsWith(w)) {
                return true;
            }
        }
        return false;
    }

    private static String stripPunctuation(String s) {
        return s.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** Часть суток -> окно времени приёма (регламент, Приложение А.3). */
    public static String[] partOfDayWindow(String part) {
        if (part == null) {
            return null;
        }
        return switch (part) {
            case "morning", "T_MORNING", "T_EARLY", "T_BEFORE_NOON" -> new String[]{"07:30", "12:00"};
            case "afternoon", "midday", "T_AFTERNOON", "T_AFTER_NOON" -> new String[]{"12:00", "16:00"};
            case "evening", "T_AFTER_WORK", "T_AFTER_SHIFT", "T_AFTER_18" -> new String[]{"16:00", "20:00"};
            default -> null;
        };
    }

    /** Человекочитаемое название части сутки — для подтверждения. */
    public static String sayPartOfDay(String part) {
        return switch (part) {
            case "morning", "early" -> "утром";
            case "midday", "afternoon" -> "после обеда";
            case "evening" -> "вечером";
            case "late" -> "позже";
            default -> "";
        };
    }

    /** Список названий месяцев в родительном — для разбора «16 октября». */
    public static List<String> monthNames() {
        return List.of(MONTHS_GEN);
    }

    /** Список дней недели в именительном — для разбора «в четверг». */
    public static List<String> weekdayNames() {
        return List.of(WEEKDAYS_NOM);
    }

    /**
     * Разбор «первого октября» / «16 октября» / «2 ноября» в конкретную дату.
     * Год берётся тот же, что и у опорной даты.
     */
    public static LocalDate parseDayMonth(String text, LocalDate reference) {
        if (text == null) {
            return null;
        }
        String q = text.toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (String token : q.split("[\\s,]+")) {
            int month = monthByName(token);
            if (month < 0) {
                continue;
            }
            // ищем число перед названием месяца в исходной строке
            int idx = q.indexOf(token);
            String before = q.substring(0, idx).replaceAll("[^\\p{L}\\p{N}\\s-]", " ").trim();
            String last = before.isEmpty() ? "" : before.split("\\s+")[before.split("\\s+").length - 1];
            int day = parseNumber(last);
            if (day < 1 || day > 31) {
                day = -1;
            }
            if (day < 0) {
                return null;  // месяц назвали, число — нет
            }
            try {
                LocalDate d = LocalDate.of(reference.getYear(), month, day);
                // «16 октября» в октябре прошедшем году — почти наверняка будущий год
                if (d.isBefore(reference.minusMonths(6))) {
                    d = d.plusYears(1);
                }
                return d;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Номер месяца по любой русской форме слова: «марта», «марте», «март», «март».
     * Возвращает -1, если это не месяц.
     *
     * Раньше здесь стояло Month.valueOf(только что найденное русское слово) —
     * а Month.valueOf понимает только английские названия (MARCH, APRIL), поэтому
     * месяц не определялся НИКОГДА и дата рождения не извлекалась.
     */
    public static int monthNumber(String token) {
        return monthByName(token);
    }

    private static int monthByName(String token) {
        String t = stripPunctuation(token);
        if (t.isEmpty()) {
            return -1;
        }
        // Родительный («первого октября») и другие падежные формы: пациент
        // говорит «в марте», «к марту», а не «в марта» — принимаем и их.
        Map<String, Integer> byGen = Map.ofEntries(
                Map.entry("января", 1), Map.entry("февраля", 2), Map.entry("марта", 3),
                Map.entry("апреля", 4), Map.entry("мая", 5), Map.entry("июня", 6),
                Map.entry("июля", 7), Map.entry("августа", 8), Map.entry("сентября", 9),
                Map.entry("октября", 10), Map.entry("ноября", 11), Map.entry("декабря", 12),
                Map.entry("январь", 1), Map.entry("февраль", 2), Map.entry("март", 3),
                Map.entry("апрель", 4), Map.entry("май", 5), Map.entry("июнь", 6),
                Map.entry("июль", 7), Map.entry("август", 8), Map.entry("сентябрь", 9),
                Map.entry("октябрь", 10), Map.entry("ноябрь", 11), Map.entry("декабрь", 12),
                Map.entry("январе", 1), Map.entry("феврале", 2), Map.entry("марте", 3),
                Map.entry("апреле", 4), Map.entry("мае", 5), Map.entry("июне", 6),
                Map.entry("июле", 7), Map.entry("августе", 8), Map.entry("сентябре", 9),
                Map.entry("октябре", 10), Map.entry("ноябре", 11), Map.entry("декабре", 12));
        return byGen.getOrDefault(t, -1);
    }

    /**
     * Дата, названная цифрами: «2026-10-16», «16.10.2026», «16.10».
     * Пациент может назвать так, а ассистент сам предлагает даты словами —
     * понимать оба формата обязательно.
     */
    public static LocalDate parseNumericDate(String text, LocalDate reference) {
        if (text == null) {
            return null;
        }
        Matcher m = Pattern.compile(
                "(\\d{4})-(\\d{1,2})-(\\d{1,2})|(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{2,4}))?")
                .matcher(text);
        if (!m.find()) {
            return null;
        }
        try {
            if (m.group(1) != null) {
                return LocalDate.of(Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            }
            int day = Integer.parseInt(m.group(4));
            int month = Integer.parseInt(m.group(5));
            int year = reference.getYear();
            if (m.group(6) != null) {
                int y = Integer.parseInt(m.group(6));
                year = y < 100 ? (y > 30 ? 1900 + y : 2000 + y) : y;
            } else if (LocalDate.of(year, month, day).isBefore(reference.minusMonths(6))) {
                year = year + 1;          // «16.10» в октябре прошедшем — это будущий год
            }
            return LocalDate.of(year, month, day);
        } catch (Exception e) {
            return null;
        }
    }

    /** Ближайшая дата с данным днём недели, начиная с from включительно. */
    public static LocalDate nextWeekday(LocalDate from, java.time.DayOfWeek target) {
        LocalDate d = from;
        for (int i = 0; i < 7; i++) {
            if (d.getDayOfWeek() == target) {
                return d;
            }
            d = d.plusDays(1);
        }
        return from;
    }

    /** Разбор текстовой части суток: «утром», «после обеда», «вечером». */
    public static String partOfDay(String text) {
        if (text == null) {
            return null;
        }
        String q = " " + text.toLowerCase(Locale.ROOT).replace('ё', 'е') + " ";
        if (q.contains("после обеда") || q.contains("после обеда") || q.contains("днем")
                || q.contains("днём") || q.contains("обед")) {
            return "afternoon";
        }
        if (q.contains("утром") || q.contains("с утра") || q.contains("пораньше")) {
            return "morning";
        }
        if (q.contains("вечером") || q.contains("после работы") || q.contains("после смен")) {
            return "evening";
        }
        if (q.contains("позже")) {
            return "late";
        }
        return null;
    }

    /** Список слов-признаков части суток — для диагностики и тестов. */
    public static List<String> partOfDayMarkers() {
        return new ArrayList<>(List.of("утром", "после обеда", "вечером", "днем", "позже"));
    }
}