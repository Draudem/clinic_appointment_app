package com.clinic.backend.voice;

import com.clinic.backend.rag.ClinicalKnowledgeBase;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * ПРАВИЛО-предохранитель для С-07 «Справка о графике работы».
 *
 * Зачем оно, если модель уже умеет отдавать {@code <SCHEDULE>}. Причина
 * конкретная и замеренная: на вопрос про график модель отвечает
 * {@code <UNSUPPORTED>} примерно в трети случаев («работает ли сегодня
 * хирург», «работаете в субботу»). Пока это так, вопрос уходит в запись —
 * а это ровно тот промах, который регламент запрещает. Правило стоит ПЕРЕД
 * моделью, читает исходный текст и потому от её ошибок не зависит.
 *
 * Порядок обработки в {@link BookingFlow}: PolicyRules → это правило → модель.
 * Именно поэтому оно должно быть здесь, а не внутри модели: «работает ли
 * сегодня хирург» содержит слово «хирург», и предохранитель намерения по
 * слову-обозначению (см. {@code ClinicalKnowledgeBase.specialtyByDesignation})
 * иначе записал бы пациента вместо ответа на вопрос.
 *
 * Правило НЕ пытается ответить само — оно только узнаёт, что вопрос про
 * график, и вытаскивает из фразы, чего именно спрашивают. Отвечает
 * {@link ScheduleQueryService}, читающий расписание из БД.
 */
public final class ScheduleInfoRule {

    private ScheduleInfoRule() {
    }

    /**
     * Слова, которые сами по себе означают вопрос про график, а не про запись.
     * «График», «режим», «расписание», «кабинет» в разговоре о записи не
     * встречаются, поэтому дополнительных условий не требуют.
     */
    private static final List<String> UNAMBIGUOUS = List.of(
            "график", "расписание", "режим работы", "режим приема",
            "кабинет", "этаж", "адрес", "праздник",
            "больнич", "отпуск", "уволил", "не принимает");

    /**
     * Слова, которые говорят про график только вместе с вопросом или днём.
     * «Работа» и «принима» без вопросительного слова — это обычный разговор
     * о записи («хочу записаться на прием»), такие фразы не перехватываем.
     */
    private static final List<String> NEEDS_QUESTION = List.of(
            "работа", "принима", "прием", "час", "скольк");

    /**
     * Признак, что фраза — вопрос. Сравнение подстрокой, поэтому сюда нельзя
     * класть «от» и «до»: они сидят внутри «работа», «потом», «сегодня»
     * и превращали бы в вопрос любое упоминание работы.
     */
    private static final List<String> QUESTION = List.of(
            "как", "когда", "где", "скольк", "какой", "какая",
            "какие", "каких", "который", "которые", "кого", "чем");

    /** Дни недели как начала слов: «средУ», «средЫ», «субботУ». */
    private static final List<String> DAY_STEMS = List.of(
            "понедельник", "вторник", "среда", "сред", "четверг", "пятниц",
            "суббот", "воскресень", "воскрес");

    /** Глаголы записи. Их наличие означает, что это НЕ вопрос про график. */
    private static final List<String> BOOKING = List.of(
            "запис", "запи", "перенес", "отмен", "переви", "заброни");

    /**
     * Глаголы в третьем лице: после них стоит имя ВРАЧА. «Когда принимает
     * Кузнецова?», «работает ли сегодня хирург». Во втором лице («как вы
     * работаете») после глагола врача нет — там про поликлинику.
     */
    private static final List<String> THIRD_PERSON = List.of(
            "принимает", "работает", "принимают", "работают");

    /** Слова, которые не являются именем врача. */
    private static final List<String> NOT_A_DOCTOR = List.of(
            "сегодня", "завтра", "вы", "она", "он", "мне", "нам", "там",
            "этот", "эта", "кто", "кого", "поликлиника", "клиника");

    /**
     * Что именно спрашивают о графике.
     *
     * @param specialty   специальность, если названа словом-обозначением врача
     * @param doctorHint  фамилия, названная после «принимает»/«работает»;
     *                    нужна, чтобы отличить «врач есть» от «такого врача
     *                    в расписании нет» (регламент, карточка С-07)
     * @param dayStem     начало названия дня недели, если день назван
     * @param today       сказано ли «сегодня»
     * @param tomorrow    сказано ли именно «завтра»
     * @param location    спрошено ли про кабинет, этаж или адрес
     * @param text        исходная фраза целиком
     */
    public record ScheduleInfo(String specialty, String doctorHint, String dayStem, boolean today,
                               boolean tomorrow, boolean location, String text) {

        public boolean hasDay() {
            return dayStem != null || today || tomorrow;
        }
    }

    /** Разбор фразы на предмет «это вопрос про график». */
    public static Optional<ScheduleInfo> detect(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String q = ClinicalKnowledgeBase.normalize(text).replace('ё', 'е');

        // «хочу записаться на прием в четверг» — это запись, а не справка
        for (String b : BOOKING) {
            if (hasStem(q, b)) {
                return Optional.empty();
            }
        }

        boolean location = false;
        boolean plain = false;
        for (String u : UNAMBIGUOUS) {
            if (q.contains(u)) {
                plain = true;
                if (u.equals("кабинет") || u.equals("этаж") || u.equals("адрес")) {
                    location = true;
                }
            }
        }

        boolean indirect = false;
        for (String n : NEEDS_QUESTION) {
            if (hasStem(q, n)) {
                indirect = true;
            }
        }
        boolean question = false;
        for (String qq : QUESTION) {
            if (q.contains(qq)) {
                question = true;
            }
        }
        boolean dayNamed = false;
        for (String d : DAY_STEMS) {
            if (hasStem(q, d)) {
                dayNamed = true;
            }
        }
        if (q.contains("сегодня") || q.contains("завтра")) {
            dayNamed = true;
        }

        if (!(plain || (indirect && (question || dayNamed)))) {
            return Optional.empty();
        }
        // «работа» без вопроса и без дня — «у меня много работы», не справка
        if (!plain && !question && !dayNamed) {
            return Optional.empty();
        }

        String dayStem = null;
        for (String d : DAY_STEMS) {
            if (hasStem(q, d)) {
                dayStem = d;
                break;
            }
        }
        boolean today = q.contains("сегодня");
        boolean tomorrow = q.contains("завтра");

        return Optional.of(new ScheduleInfo(
                ClinicalKnowledgeBase.specialtyByDesignation(text),
                doctorHint(q),
                dayStem, today, tomorrow, location, text));
    }

    /**
     * Фамилия врача, названная после глагола в третьем лице.
     *
     * Только так можно отличить «Такого врача в расписании нет» (регламент,
     * карточка С-07) от общего ответа про поликлинику: без этой подсказки
     * вопрос «Когда принимает Кузнецова?» выглядит так же, как «Как вы
     * работаете?». Слово-обозначение специальности («хирург») сюда не
     * попадает — его разбирает {@link #specialtyByDesignation}.
     */
    private static String doctorHint(String q) {
        int after = -1;
        int verbEnd = -1;
        for (String v : THIRD_PERSON) {
            int at = q.indexOf(v);
            if (at < 0) {
                continue;
            }
            // глагол должен быть САМ ПО СЕБЕ, иначе «работаете» (второе лицо,
            // про поликлинику) подойдёт под «работает» (третье лицо, про врача)
            int end = at + v.length();
            boolean leftOk = at == 0 || !Character.isLetter(q.charAt(at - 1));
            boolean rightOk = end >= q.length() || !Character.isLetter(q.charAt(end));
            if (leftOk && rightOk && (after < 0 || at < after)) {
                after = at;
                verbEnd = end;
            }
        }
        if (after < 0) {
            return null;
        }
        // начинаем ПОСЛЕ глагола: сам глагол фамилией не является
        for (String word : q.substring(verbEnd).split("[^а-яa-z]+")) {
            if (word.length() < 4) {
                continue;
            }
            if (NOT_A_DOCTOR.contains(word)) {
                continue;
            }
            if (ClinicalKnowledgeBase.specialtyByDesignation(word) != null) {
                continue;
            }
            boolean weekday = false;
            for (String d : DAY_STEMS) {
                if (word.startsWith(d)) {
                    weekday = true;
                    break;
                }
            }
            if (weekday) {
                continue;
            }
            return word;
        }
        return null;
    }

    /** Начинается ли текст со слова, допуская русское окончание. */
    public static boolean hasStem(String q, String stem) {
        int from = 0;
        while (true) {
            int at = q.indexOf(stem, from);
            if (at < 0) {
                return false;
            }
            boolean leftOk = at == 0 || !Character.isLetter(q.charAt(at - 1));
            int end = at + stem.length();
            // окончание допускаем, но не бесконечное: иначе «сред» поймал бы
            // «среду», а «час» — «часов» и «часовник»
            int tail = 0;
            while (end + tail < q.length() && Character.isLetter(q.charAt(end + tail))) {
                tail++;
            }
            boolean rightOk = tail <= SUFFIX_SLACK;
            if (leftOk && rightOk) {
                return true;
            }
            from = at + 1;
        }
    }

    /** Длина окончания, которое ещё считаем тем же словом: «средЫ», «принимаЕТ». */
    private static final int SUFFIX_SLACK = 4;

    /** Для сообщений об ошибках и тестов: нормализованный вид фразы. */
    public static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }
}
