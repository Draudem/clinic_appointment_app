package com.clinic.backend.voice;

import com.clinic.backend.rag.ClinicalKnowledgeBase;

import java.util.List;
import java.util.Locale;

/**
 * Сквозные правила регламента, которые проверяются на КАЖДОЙ реплике
 * пациента до сценария (раздел 7.3, правило R-01 проверяется всегда).
 *
 * Здесь всё, что обязано сработать раньше записи:
 *   - красные флаги А и Б (7.4) — прерывают любой сценарий;
 *   - просьба человека (R-02) — немедленный перевод, без уговоров;
 *   - жалоба (R-03);
 *   - медицинский вопрос (R-04) — граничная фраза, без советов;
 *   - разбор в трубке (3.2 «Честность») — всегда виртуальный помощник;
 *   - отказ от записи разговора (3.4).
 *
 * Лексика — из Приложения Д (контрольные диалоги) и раздела 7.4.
 * Это страховочный слой над моделью: даже если модель ошиблась в интенте,
 * опасная фраза всё равно приводит к нужному действию.
 */
public final class PolicyRules {

    private PolicyRules() {
    }

    /** Что нужно сделать ассистенту: продолжить сценарий, перевести или закончить. */
    public enum Decision {
        CONTINUE,
        TRANSFER,
        FINISH
    }

    /** Результат проверки. */
    public record Verdict(Decision decision, String reply, String rule, String resultCode) {

        public boolean blocksScenario() {
            return decision != Decision.CONTINUE;
        }
    }

    // --- красные флаги категории А: возможна угроза жизни (7.4) ---

    private static final List<String> RED_A = List.of(
            "боль в груди", "болит грудь", "давит за грудиной", "давит в груди",
            "жжёт в груди", "жжет в груди", "отдаёт в левую руку", "отдает в левую руку",
            "сердечный приступ", "инфаркт",
            "не может дышать", "не могу дышать", "задыхается", "синеет", "подавился",
            "потерял сознание", "без сознания", "не приходит в себя", "не могу разбудить",
            "в обморок и не встаёт", "в обморок и не встает",
            "перекосило лицо", "отнялась рука", "отнялась нога", "онемела половина тела",
            "судороги", "трясёт не останавливается", "припадок",
            "сильное кровотечение", "кровь не останавливается", "рвота кровью", "кровь изо рта",
            "дтп", "сильно ударился головой", "упал с высоты", "отравился",
            "наглотался таблеток", "укусила змея", "ожог большой",
            "отекает горло", "не может глотать", "анафилактический шок",
            "младенец вялый", "ребёнок посинел", "ребенок посинел", "ребёнок проглотил",
            "отошли воды", "начались схватки", "беременная кровотечение",
            "не хочу жить", "покончить с собой", "угрожает убить");

    // --- красные флаги категории Б: острое состояние ---

    private static final List<String> RED_B = List.of(
            "очень сильная боль", "невыносимо болит", "боль усиливается",
            "температура сорок", "температура не сбивается", "температура третий день",
            "стало намного хуже", "резко поднялось давление", "кружится голова не могу встать",
            "подвернул ногу", "порезался глубоко");

    // --- просьба человека (R-02) ---

    private static final List<String> OPERATOR_WORDS = List.of(
            "оператор", "человек", "сотрудник", "регистратор", "живой", "соедините",
            "соедини", "переключи", "переведи", "переведите", "администратор");

    // --- жалоба (R-03) ---

    private static final List<String> COMPLAINT_WORDS = List.of(
            "пожаловаться", "жалоба", "безобразие", "нагрубил", "нахамил", "хамство",
            "напишу в министерство", "в министерство", "некачественно", "безответственно");

    // --- медицинский вопрос (R-04, граничные условия 3.8) ---

    private static final List<String> MEDICAL_WORDS = List.of(
            "что выпить", "что принять", "что пить", "какое лекарство", "какие лекарства",
            "посоветуй", "что делать если", "продиктуйте результат", "какие показатели", "что там в анализах",
            "хорошие ли анализы", "плохие ли анализы", "нормальный ли у меня", "какой у меня анализ",
            "что показывает", "расшифруй анализ", "поставь диагноз", "что у меня");

    /** Проверяет реплику. Регистр не важен — речь приходит из распознавания. */
    public static Verdict check(String text) {
        String q = ClinicalKnowledgeBase.normalize(text);

        if (containsAny(q, RED_A)) {
            return new Verdict(Decision.TRANSFER,
                    "Пожалуйста, немедленно звоните в скорую помощь: сто три или сто двенадцать. "
                            + "Повторяю: сто три или сто двенадцать. Не ждите приёма в поликлинике.",
                    "R-01", "EMERGENCY");
        }
        if (containsAny(q, RED_B)) {
            return new Verdict(Decision.TRANSFER,
                    "Соединяю с регистратором, он организует срочную помощь. "
                            + "Если станет хуже — сразу звоните сто три.",
                    "R-11", "EMERGENCY");
        }
        if (containsAny(q, OPERATOR_WORDS)) {
            return new Verdict(Decision.TRANSFER,
                    "Соединяю с сотрудником. Суть вашего вопроса уже передана — повторять не придётся.",
                    "R-02", "ESCALATED");
        }
        if (containsAny(q, COMPLAINT_WORDS)) {
            return new Verdict(Decision.TRANSFER,
                    "Мне жаль, что так вышло. Соединяю вас со старшим администратором.",
                    "R-03", "COMPLAINT");
        }
        if (containsAny(q, MEDICAL_WORDS)) {
            return new Verdict(Decision.TRANSFER,
                    "Оценить состояние может только врач. Соединяю с регистратором — "
                            + "он подскажет, как быстрее попасть к врачу.",
                    "R-04", "ESCALATED");
        }
        if (matches(q, "вы робот", "это робот", "вы человек", "вы настоящий")) {
            return new Verdict(Decision.FINISH,
                    "Да, я виртуальный помощник регистратуры. Если хотите, соединю с сотрудником.",
                    "R-02", "INFO_GIVEN");
        }
        if (matches(q, "не записывайте", "я против записи", "не записывай", "без записи")) {
            // Отказ от записи разговора: обслуживание продолжается (3.4).
            return new Verdict(Decision.CONTINUE,
                    "Хорошо, запись разговора отключена.", "3.4", null);
        }
        return new Verdict(Decision.CONTINUE, null, null, null);
    }

    /** Отказ от записи разговора — флаг для стенограммы, чтобы не сохранять аудио. */
    public static boolean isRecordingOptOut(String text) {
        String q = ClinicalKnowledgeBase.normalize(text);
        return matches(q, "не записывайте", "я против записи", "не записывай", "без записи");
    }

    private static final String VOWELS = "аеиоуыэюя";

    /**
     * Неразборчивая реплика — признак шума в линии, а не речи.
     *
     * Раньше правило искало «три согласных подряд», и из-за него нормальное
     * слово «записаться» (…т, ь, с…) считалось шумом — сценарий записи не начинался.
     * Теперь признак один и надёжный: в реплике НЕТ гласных вообще. Слова
     * «записаться», «вторник», «ЛОР» гласные содержат и проходят.
     */
    public static boolean isGarbage(String text) {
        if (text == null) {
            return true;
        }
        String letters = ClinicalKnowledgeBase.normalize(text).replaceAll("[^\\p{L}]", "");
        if (letters.isEmpty()) {
            return true;
        }
        if (letters.length() == 1) {
            return true;                       // одиночное «а», «у» — это шум
        }
        for (int i = 0; i < letters.length(); i++) {
            if (VOWELS.indexOf(letters.charAt(i)) >= 0) {
                return false;                  // есть гласная — это речь
            }
        }
        return true;
    }

    /**
     * На этапах, где ждут «да» или «нет», проверка на шум не нужна:
     * «да» — это две гласные, но осмысленный ответ.
     */
    public static boolean expectsYesNo(String stage) {
        return "PENDING_CONFIRMATION".equals(stage) || "DUP".equals(stage);
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** Совпадение с одной из фраз — целиком либо как отдельное предложение. */
    private static boolean matches(String text, String... phrases) {
        for (String p : phrases) {
            if (java.util.regex.Pattern.compile("\\b" + p + "\\b").matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /** Ответ на «да/нет» — общий для сценария и для подтверждений. */
    public enum Answer { YES, NO, UNKNOWN }

    private static final List<String> YES = List.of(
            "да", "ага", "угу", "ок", "окей", "хорошо", "верно", "подтверждаю", "правильно",
            "согласен", "согласна", "давай", "давайте", "все верно", "всё верно", "конечно",
            "именно так", "точно");

    private static final List<String> NO = List.of(
            "нет", "неверно", "не надо", "не нужно", "не хочу", "отмена", "отменить",
            "отказ", "стоп", "не то", "другое", "неа");

    /**
     * Ответ на «да/нет»: решает ПОСЛЕДНЕЕ сказанное слово.
     *
     * <p>Раньше сначала искалось любое слово из NO, затем любое из YES, а
     * короткое слово требовало, чтобы вся реплика была ровно этим словом.
     * Из-за этого «…восемь тридцать верно нет» считалось согласием: «верно»
     * длинное и искалось подстрокой, а «нет» короткое и не совпадало.
     * Пациент отказывался — а его записывали. Теперь отказ, сказанный после
     * согласия, отменяет согласие, и наоборот.
     *
     * <p>Применяется только там, где сценарий ждёт ответа: см.
     * {@link #expectsYesNo(String)}. На этапе выбора слота реплика «да»
     * ничего не значит, там нужен конкретный день и время.
     */
    public static Answer answer(String text) {
        String q = ClinicalKnowledgeBase.normalize(text)
                .replaceAll("[^\\p{L}\\p{N}+\\- ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (q.isEmpty()) {
            return Answer.UNKNOWN;
        }
        Answer last = Answer.UNKNOWN;
        int lastAt = -1;
        // Оба списка обходятся вместе: важен не порядок проверки, а позиция.
        // Отдельно NO и YES проверять нельзя — тогда первое найденное и снова
        // выигрывает не то, что сказал пациент последним.
        for (String w : NO) {
            int at = position(q, w);
            if (at > lastAt) {
                lastAt = at;
                last = Answer.NO;
            }
        }
        for (String w : YES) {
            int at = position(q, w);
            if (at > lastAt) {
                lastAt = at;
                last = Answer.YES;
            }
        }
        return last;
    }

    /**
     * Позиция ПОСЛЕДНЕГО вхождения слова в фразу, либо -1 если его нет.
     *
     * <p>Именно последнего: правило «решает последнее сказанное» иначе
     * нарушилось бы на слове, которое пациент произнёс дважды.
     *
     * <p>Короткие слова ищем ТОЛЬКО как целые. Подстрочный поиск здесь дал бы
     * катастрофу: «не» нашлось бы внутри «поНЕдельник», и любая фраза с днём
     * недели превратилась бы в отказ от записи.
     */
    private static int position(String q, String word) {
        if (word.length() <= 3) {
            int found = -1;
            int from = 0;
            while (true) {
                int at = q.indexOf(word, from);
                if (at < 0) {
                    return found;
                }
                boolean leftOk = at == 0 || q.charAt(at - 1) == ' ';
                int end = at + word.length();
                boolean rightOk = end >= q.length() || q.charAt(end) == ' ';
                if (leftOk && rightOk) {
                    found = at;          // запоминаем ПОСЛЕДНЕЕ целое вхождение
                }
                from = at + 1;
            }
        }
        return q.lastIndexOf(word);
    }

    /** Порядковый номер варианта: «второй», «2», «второй вариант». */
    public static Integer ordinalChoice(String text) {
        String q = ClinicalKnowledgeBase.normalize(text).replaceAll("[^\\p{L}\\p{N}\\s]", " ").trim();
        if (q.isEmpty()) {
            return null;
        }
        String[] tokens = q.split("\\s+");
        for (String t : tokens) {
            if (t.matches("\\d{1,2}")) {
                int n = Integer.parseInt(t);
                if (n >= 1 && n <= 9) {
                    return n - 1;
                }
            }
            int n = RussianTime.parseOrdinal(t);
            if (n >= 1 && n <= 9) {
                return n - 1;
            }
        }
        return null;
    }

    /** Ответ для сценария «не понял» — лестница R-05: 1 раз переспросить, 2 — предложить, 3 — перевести. */
    public static String misunderstandingReply(int misses) {
        return switch (misses) {
            case 0, 1 -> "Уточните, пожалуйста: вы хотите записаться на приём?";
            case 2 -> "Похоже, я вас плохо понимаю. Соединю с сотрудником — так будет быстрее.";
            default -> "Соединяю с сотрудником.";
        };
    }

    /** Фраза при третьей неудаче (R-05, перевод автоматически). */
    public static boolean shouldTransferAfterMisses(int misses) {
        return misses >= 3;
    }

    public static String norm(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
    }
}