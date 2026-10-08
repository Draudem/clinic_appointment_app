package com.clinic.assistant.ai.stub;

import com.clinic.assistant.ai.IntentParserClient;
import com.clinic.assistant.ai.frame.Intent;
import com.clinic.assistant.ai.frame.VoiceFrame;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ЗАГЛУШКА этапа парсинга: правила и словари вместо нейросети.
 *
 * <p>Работает по тому же контракту, что и настоящая модель, и возвращает
 * тот же {@link VoiceFrame} — весь дальнейший код конвейера не различает их.</p>
 *
 * <p>Хорошо подходит для демо и для разработки фронтенда/мобильного приложения,
 * пока коллега готовит сервис с ONNX-моделью.</p>
 */
public class StubIntentParserClient implements IntentParserClient {

    /** Ключевое слово → токен специальности модели. */
    private static final Map<String, String> SPECIALTY_KEYWORDS = new LinkedHashMap<>();

    static {
        SPECIALTY_KEYWORDS.put("стоматолог", "DENTIST");
        SPECIALTY_KEYWORDS.put("зуб", "DENTIST");
        SPECIALTY_KEYWORDS.put("дерматолог", "DERMATOLOGIST");
        SPECIALTY_KEYWORDS.put("кожн", "DERMATOLOGIST");
        SPECIALTY_KEYWORDS.put("гинеколог", "GYNECOLOGIST");
        SPECIALTY_KEYWORDS.put("женск", "GYNECOLOGIST");
        SPECIALTY_KEYWORDS.put("офтальмолог", "OPHTHALMOLOGIST");
        SPECIALTY_KEYWORDS.put("окulist", "OPHTHALMOLOGIST");
        SPECIALTY_KEYWORDS.put("глазн", "OPHTHALMOLOGIST");
        SPECIALTY_KEYWORDS.put("невролог", "NEUROLOGIST");
        SPECIALTY_KEYWORDS.put("невропатолог", "NEUROLOGIST");        SPECIALTY_KEYWORDS.put("психолог", "PSYCHOLOGIST");
        SPECIALTY_KEYWORDS.put("психотерапевт", "PSYCHOLOGIST");
        SPECIALTY_KEYWORDS.put("психиатр", "PSYCHOLOGIST");
        SPECIALTY_KEYWORDS.put("лор", "ENT");
        SPECIALTY_KEYWORDS.put("ухо", "ENT");
        SPECIALTY_KEYWORDS.put("горл", "ENT");
        SPECIALTY_KEYWORDS.put("нос", "ENT");
        SPECIALTY_KEYWORDS.put("терапевт", "GP");
        SPECIALTY_KEYWORDS.put("врач общ", "GP");
        SPECIALTY_KEYWORDS.put("семейн", "GP");
    }

    /** Ключевое слово → токен симптома модели. */
    private static final Map<String, String> SYMPTOM_KEYWORDS = new LinkedHashMap<>();

    static {
        SYMPTOM_KEYWORDS.put("мигрень", "SYMPTOM_HEADACHE");
        SYMPTOM_KEYWORDS.put("голов", "SYMPTOM_HEADACHE");
        SYMPTOM_KEYWORDS.put("зуб", "SYMPTOM_TOOTHACHE");
        SYMPTOM_KEYWORDS.put("горл", "SYMPTOM_SORE_THROAT");
        SYMPTOM_KEYWORDS.put("боль в горл", "SYMPTOM_SORE_THROAT");
        SYMPTOM_KEYWORDS.put("температур", "SYMPTOM_FEVER");
        SYMPTOM_KEYWORDS.put("жар", "SYMPTOM_FEVER");
        SYMPTOM_KEYWORDS.put("сып", "SYMPTOM_RASH");
        SYMPTOM_KEYWORDS.put("высыпан", "SYMPTOM_RASH");
        SYMPTOM_KEYWORDS.put("спин", "SYMPTOM_BACK_PAIN");
        SYMPTOM_KEYWORDS.put("пояниц", "SYMPTOM_BACK_PAIN");
        SYMPTOM_KEYWORDS.put("глаз", "SYMPTOM_EYE_PAIN");
        SYMPTOM_KEYWORDS.put("тревог", "SYMPTOM_ANXIETY");
        SYMPTOM_KEYWORDS.put("кашл", "SYMPTOM_COUGH");
        SYMPTOM_KEYWORDS.put("живот", "SYMPTOM_STOMACH_PAIN");
        SYMPTOM_KEYWORDS.put("желуд", "SYMPTOM_STOMACH_PAIN");
        SYMPTOM_KEYWORDS.put("тошнот", "SYMPTOM_STOMACH_PAIN");
        SYMPTOM_KEYWORDS.put("головокруж", "SYMPTOM_DIZZINESS");
        SYMPTOM_KEYWORDS.put("бессонниц", "SYMPTOM_INSOMNIA");
        SYMPTOM_KEYWORDS.put("не могу уснуть", "SYMPTOM_INSOMNIA");
        SYMPTOM_KEYWORDS.put("аллерг", "SYMPTOM_ALLERGY");
    }

    private static final Map<String, Integer> HOUR_WORDS = new LinkedHashMap<>();

    static {
        HOUR_WORDS.put("один", 1);
        HOUR_WORDS.put("одна", 1);
        HOUR_WORDS.put("два", 2);
        HOUR_WORDS.put("две", 2);
        HOUR_WORDS.put("три", 3);
        HOUR_WORDS.put("четыре", 4);
        HOUR_WORDS.put("пять", 5);
        HOUR_WORDS.put("шесть", 6);
        HOUR_WORDS.put("семь", 7);
        HOUR_WORDS.put("восемь", 8);
        HOUR_WORDS.put("девять", 9);
        HOUR_WORDS.put("десять", 10);
        HOUR_WORDS.put("одиннадцать", 11);
        HOUR_WORDS.put("двенадцать", 12);
        HOUR_WORDS.put("тринадцать", 13);
        HOUR_WORDS.put("четырнадцать", 14);
        HOUR_WORDS.put("пятнадцать", 15);
        HOUR_WORDS.put("шестнадцать", 16);
        HOUR_WORDS.put("семнадцать", 17);
        HOUR_WORDS.put("восемнадцать", 18);
        HOUR_WORDS.put("девятнадцать", 19);
        HOUR_WORDS.put("двадцать", 20);
    }

    private static final Map<String, String> WEEKDAYS = new LinkedHashMap<>();

    static {
        WEEKDAYS.put("понедельник", "T_MON");
        WEEKDAYS.put("вторник", "T_TUE");
        WEEKDAYS.put("среду", "T_WED");
        WEEKDAYS.put("среда", "T_WED");
        WEEKDAYS.put("четверг", "T_THU");
        WEEKDAYS.put("пятниц", "T_FRI");
        WEEKDAYS.put("суббот", "T_SAT");
        WEEKDAYS.put("воскресенье", "T_SUN");
        WEEKDAYS.put("воскресение", "T_SUN");
    }

    @Override
    public VoiceFrame parse(String text) {
        String t = normalize(text);

        Intent intent = detectIntent(t);
        String specialty = detectSpecialty(t);
        List<String> timeFactors = detectTime(t);
        List<String> symptoms = detectSymptoms(t);

        if (intent == Intent.UNSUPPORTED) {
            return VoiceFrame.unsupported();
        }
        return VoiceFrame.of(intent, specialty, timeFactors, symptoms, false);
    }

    private static Intent detectIntent(String t) {
        if (t.contains("отмен") || t.contains("не буду") || t.contains("убрать запис")) {
            return Intent.CANCEL;
        }
        if (t.contains("перенес") || t.contains("другое время") || t.contains("другое число")
                || t.contains("изменить запис") || t.contains("сменить время")) {
            return Intent.RESCHEDULE;
        }
        if (t.contains("запиш") || t.contains("записаться") || t.contains("запись")
                || t.contains("записать") || t.contains("попасть") || t.contains("прием")
                || t.contains("приём") || t.contains("нужен врач")) {
            return Intent.BOOK;
        }
        return Intent.UNSUPPORTED;
    }

    private static String detectSpecialty(String t) {
        for (Map.Entry<String, String> e : SPECIALTY_KEYWORDS.entrySet()) {
            if (t.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null; // неизвестно → дальше конвейер попробует найти специальность по тексту в БД
    }

    private static List<String> detectSymptoms(String t) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, String> e : SYMPTOM_KEYWORDS.entrySet()) {
            if (t.contains(e.getKey()) && !result.contains(e.getValue())) {
                result.add(e.getValue());
            }
        }
        return result;
    }

    private static List<String> detectTime(String t) {
        List<String> factors = new ArrayList<>();

        // --- дата ---
        if (t.contains("послезавтра")) {
            factors.add("T_DAY_AFTER_TOMORROW");
        } else if (t.contains("завтра")) {
            factors.add("T_TOMORROW");
        } else if (t.contains("сегодня")) {
            factors.add("T_TODAY");
        }
        for (Map.Entry<String, String> e : WEEKDAYS.entrySet()) {
            if (t.contains(e.getKey())) {
                factors.add(e.getValue());
                break;
            }
        }
        if (t.contains("следующ") && t.contains("недел")) {
            factors.add("T_NEXT_WEEK");
        } else if (t.contains("этой неделе") || t.contains("на этой неделе")) {
            factors.add("T_THIS_WEEK");
        }
        if (t.contains("выходн")) {
            factors.add("T_WEEKEND");
        }

        // --- часть дня ---
        if (containsAny(t, "утр", "раннее утро")) {
            factors.add("T_MORNING");
        } else if (containsAny(t, "после обед", "после полуден", "дневное время", "днем")) {
            factors.add("T_AFTERNOON");
        } else if (t.contains("вечер")) {
            factors.add("T_EVENING");
        } else if (t.contains("после работы")) {
            factors.add("T_AFTER_WORK");
        }

        // --- конкретное время: «в 10:30» ---
        var digits = java.util.regex.Pattern.compile("(\\d{1,2})[:.](\\d{2})").matcher(t);
        if (digits.find()) {
            int h = Integer.parseInt(digits.group(1));
            int m = Integer.parseInt(digits.group(2));
            if (h <= 23 && m <= 59) {
                factors.add("T_H%02d_%02d".formatted(h, m));
            }
        } else {
            // «в 15 часов»
            var numericHours = java.util.regex.Pattern.compile("(?:^|\\s)в\\s+(\\d{1,2})\\s+час").matcher(t);
            if (numericHours.find()) {
                factors.add("T_H%02d_00".formatted(adjustHour(Integer.parseInt(numericHours.group(1)), t)));
            } else {
                // «в десять утра», «в три часа дня» — ищем первое «в + числительное»
                var words = java.util.regex.Pattern.compile("(?:^|\\s)в\\s+([а-яё]+)(?:\\s|$)").matcher(t);
                while (words.find()) {
                    Integer hour = HOUR_WORDS.get(words.group(1));
                    if (hour != null) {
                        factors.add("T_H%02d_00".formatted(adjustHour(hour, t)));
                        break;
                    }
                }
            }
        }

        if (factors.isEmpty()) {
            factors.add("T_UNKNOWN");
        }
        return factors;
    }

    /** «в три часа дня» → 15, «в десять вечера» → 22. */
    private static int adjustHour(int hour, String text) {
        if (hour <= 11 && (text.contains("вечер") || text.contains("дня"))) {
            return hour + 12;
        }
        return hour;
    }

    private static boolean containsAny(String text, String... variants) {
        for (String v : variants) {
            if (text.contains(v)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String text) {
        return (text == null ? "" : text)
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .trim();
    }
}
