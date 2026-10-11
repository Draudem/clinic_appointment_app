package com.clinic.backend.voice;

import com.clinic.backend.rag.ClinicalKnowledgeBase;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Детерминированный парсер-заглушка (без нейросети). Используется, когда
 * app.voice.parser=rule или NLU-модель недоступна. Понимает базовые интенты,
 * специальности (через RAG-словарь) и простые выражения времени, выдавая те же
 * имена факторов, что и модель, — чтобы TimeResolver работал одинаково.
 */
public class RuleCommandParser implements CommandParser {

    private final ClinicalKnowledgeBase knowledgeBase;

    public RuleCommandParser(ClinicalKnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    @Override
    public String name() {
        return "rule";
    }

    private static final Map<DayOfWeek, String> WEEKDAY_TOKENS = Map.of(
            DayOfWeek.MONDAY, "T_MON", DayOfWeek.TUESDAY, "T_TUE", DayOfWeek.WEDNESDAY, "T_WED",
            DayOfWeek.THURSDAY, "T_THU", DayOfWeek.FRIDAY, "T_FRI", DayOfWeek.SATURDAY, "T_SAT",
            DayOfWeek.SUNDAY, "T_SUN");

    /** «в 10:30», «15:00» -> точное время. */
    private static final Pattern HHMM = Pattern.compile("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)");

    @Override
    public ParsedCommand parse(String text) {
        String q = ClinicalKnowledgeBase.normalize(text);

        String intent = "BOOK";
        if (containsAny(q, "отмен")) {
            intent = "CANCEL";
        } else if (containsAny(q, "перенес", "перезапиш", "сдвинь", "измен")) {
            intent = "RESCHEDULE";
        }

        String specialty = knowledgeBase.recommendSpecialty(text);

        List<String> timeFactors = new ArrayList<>();
        if (containsAny(q, "послезавтра")) {
            timeFactors.add("T_DAY_AFTER_TOMORROW");
        } else if (containsAny(q, "завтра")) {
            timeFactors.add("T_TOMORROW");
        } else if (containsAny(q, "сегодня")) {
            timeFactors.add("T_TODAY");
        }
        if (containsAny(q, "следующ", "на следующей")) {
            timeFactors.add("T_NEXT_WEEK");
        }
        for (Map.Entry<DayOfWeek, String> e : WEEKDAY_TOKENS.entrySet()) {
            String ru = ruWeekday(e.getKey());
            if (q.contains(ru)) {
                timeFactors.add(e.getValue());
                break;
            }
        }
        if (containsAny(q, "выходн", "суббот", "воскрес")) {
            timeFactors.add("T_WEEKEND");
        }
        if (containsAny(q, "утр", "пораньше", "раньше")) {
            timeFactors.add("T_MORNING");
        } else if (containsAny(q, "вечер", "после работ", "после смен", "позже")) {
            timeFactors.add("T_EVENING");
        } else if (containsAny(q, "днём", "днем", "после обеда", "обед")) {
            timeFactors.add("T_AFTERNOON");
        } else if (containsAny(q, "скорее", "как можно")) {
            timeFactors.add("T_SOON");
        }

        // точное время «HH:MM» — важно для коротких ответов на подтверждение
        Matcher m = HHMM.matcher(q);
        if (m.find()) {
            int hour = Integer.parseInt(m.group(1));
            int minute = Integer.parseInt(m.group(2));
            timeFactors.removeIf(f -> f.startsWith("T_H"));
            timeFactors.add(String.format("T_H%02d_%02d", hour, minute));
        }

        boolean timeUnknown = timeFactors.isEmpty();
        boolean unsupported = "UNSUPPORTED".equals(intent);
        boolean needsClarification = !unsupported && specialty == null;

        return new ParsedCommand(intent, specialty, timeFactors, List.of(),
                timeUnknown, needsClarification, unsupported, "(rule)", "rule");
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static String ruWeekday(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "понедельник";
            case TUESDAY -> "вторник";
            case WEDNESDAY -> "сред";
            case THURSDAY -> "четверг";
            case FRIDAY -> "пятниц";
            case SATURDAY -> "суббот";
            case SUNDAY -> "воскрес";
        };
    }
}
