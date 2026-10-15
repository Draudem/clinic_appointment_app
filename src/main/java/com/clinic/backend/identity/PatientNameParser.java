package com.clinic.backend.identity;

import com.clinic.backend.rag.ClinicalKnowledgeBase;
import com.clinic.backend.voice.RussianTime;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбирает то, что пациент назвал про себя: ФИО, дату рождения, полис, телефон.
 *
 * ПОЧЕМУ БЕЗ НЕЙРОСЕТИ. Идентификация — это не «понять смысл», а сопоставить
 * услышанное со справочником пациентов. Нейросеть здесь опасна: она склонна
 * «дорисовывать» имя, которого не было, а регламент 3.5 запрещает ассистенту
 * называть чужие персональные данные вслух. Ошибка здесь стоит чужой записи
 * в чужую карту. Разбор детерминированный: те же слова — тот же результат,
 * и результат всегда можно показать в логе.
 *
 * Поддерживаются формы, которые реально звучат в трубке:
 *   «Смирнова Ольга Викторовна, двенадцатое марта семьдесят восьмого»
 *   «Петров Игорь Николаевич, пятьдесят второго года»
 *   «Соколов, двенадцатое три семьдесят восемь»
 *   «мой полис 2203 12 34 56 78 90 1»
 */
public final class PatientNameParser {

    private PatientNameParser() {
    }

    /** Что удалось вытащить из реплики о пациенте. */
    public record Data(String fio, String surname, LocalDate birthDate,
                       String policyNo, String phone) {

        /**
         * Названы ли фамилия И имя.
         *
         * Недостаточно назвать ОДНО слово: на вопрос об имени пациент может ответить
         * «Кузьмина», но и распознавание может услышать «да» или «завтра» вместо
         * фамилии. Одно слово разбирается как кандидат в фамилию (surname),
         * а полное ФИО считаем только когда есть и имя, и фамилия.
         */
        public boolean hasFio() {
            return fio != null && !fio.isBlank();
        }

        /** Названа ли хотя бы фамилия. */
        public boolean hasSurname() {
            return surname != null && !surname.isBlank();
        }

        public boolean hasBirthDate() {
            return birthDate != null;
        }

        public static Data empty() {
            return new Data(null, null, null, null, null);
        }
    }

    /** 15- или 16-значный полис ОМС, слитно или с пробелами. */
    private static final Pattern POLICY = Pattern.compile("(\\d[\\d\\s-]{14,20}\\d)");

    /**
     * Зачинки, после которых начинается ФИО. Вырезаются из реплики перед разбором,
     * чтобы «меня зовут …» не превратилось в фамилию «Меня».
     */
    private static final List<String> LEAD_MARKERS = List.of(
            "меня зовут", "моя фамилия", "моё отчество", "мое отчество",
            "зовут", "фамилия", "фамилию", "имя", "отчество", "запишите", "запиши");

    /**
     * Слова, которые не могут быть частью ФИО: служебные и глаголы.
     * Короткие и частые имена сюда НЕ входят намеренно.
     */
    private static final List<String> STOP_WORDS = List.of(
            "это", "я", "мне", "меня", "зовите", "записать", "записаться", "запись",
            "хочу", "нужно", "надо", "пожалуйста", "пациент", "пациентка",
            "моя", "мое", "моё", "мои", "и", "а", "но", "итак", "значит", "слушайте",
            "талон", "талончик", "записка", "приём", "прием", "врач", "доктор",
            "поликлиника", "регистратура", "телефон", "номер", "время", "дата",
            "запись", "записаться", "записать", "дайте", "дай", "хочу", "нужно",
            // предлоги и служебные словочки: без них в ФИО попадало «К Стоматологу»
            "к", "у", "в", "на", "по", "из", "со", "до", "для", "же", "бы", "ли",
            "то", "не", "нет", "или", "вот", "там", "тут", "мне", "мною", "нас", "вас");

    /**
     * ФИО из реплики.
     *
     * Раньше здесь искались слова С ЗАГЛАВНОЙ буквы. Распознавание речи отдаёт
     * текст строчными («смирнова ольга викторовна…»), и фамилия не находилась
     * вообще: ассистент по кругу просил назвать её заново. Поэтому берём первые
     * три слова, которые могут быть именем, независимо от регистра.
     *
     * Из кандидатов выбрасываются числа, порядковые числительные (это части даты
     * рождения), названия месяцев и дней недели — иначе в ФИО попало бы
     * «двенадцатое марта».
     */
    static String fio(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        // убираем зачинки: «меня зовут …», «это …», «моя фамилия …»
        String q = text.toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (String lead : LEAD_MARKERS) {
            q = q.replace(lead, " ");
        }
        q = q.replaceAll("[^\\p{L}\\s-]", " ");

        List<String> names = new ArrayList<>();
        for (String raw : q.split("\\s+")) {
            String w = raw.trim();
            if (w.isEmpty() || names.size() >= 3) {
                continue;
            }
            if (w.length() < 2 || STOP_WORDS.contains(w) || isNoise(w) || isDateWord(w)) {
                continue;
            }
            names.add(capitalize(w));
        }
        return names.isEmpty() ? null : String.join(" ", names);
    }

    /** Часть даты рождения, а не имени. */
    private static boolean isDateWord(String w) {
        if (w.matches(".*\\d.*")) {
            return true;
        }
        if (RussianTime.parseNumber(w) > 0 || RussianTime.parseOrdinal(w) > 0) {
            return true;
        }
        return RussianTime.monthNumber(w) > 0;
    }

    private static boolean isMonthLike(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        for (String m : RussianTime.monthNames()) {
            if (w.equals(m)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Название специальности не фамилия.
     *
     * Без этой проверки «хочу записаться к стоматологу» давало ФИО «Стоматологу»,
     * и ассистент вместо просьбы назвать фамилию просил дату рождения.
     */
    private static boolean isSpecialty(String word) {
        String w = word.toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (String name : ClinicalKnowledgeBase.SYNONYMS.keySet()) {
            if (name.toLowerCase(Locale.ROOT).replace('ё', 'е').contains(w)
                    || w.contains(name.toLowerCase(Locale.ROOT).replace('ё', 'е'))) {
                return true;
            }
        }
        for (List<String> syn : ClinicalKnowledgeBase.SYNONYMS.values()) {
            for (String s : syn) {
                if (s.equals(w) || (w.length() >= 4 && s.contains(w))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isNoise(String word) {
        String w = word.toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (isSpecialty(w)) {
            return true;
        }
        if (STOP_WORDS.contains(w)) {
            return true;
        }
        if (isMonthLike(w)) {
            return true;
        }
        // дни недели: «в среду», «четверг»
        for (String d : RussianTime.weekdayNames()) {
            if (d.startsWith(w) && w.length() >= 4) {
                return true;
            }
        }
        return false;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /**
     * Главная точка входа: что пациент назвал про себя.
     *
     * Каждый элемент разбирается независимо, потому что пациент может назвать
     * только часть: «Соколов» без даты, или дату без отчества. Сценарий спросит
     * недостающее отдельно — по регламенту нельзя подсказывать данные самому.
     */
    public static Data parse(String text) {
        if (text == null || text.isBlank()) {
            return Data.empty();
        }
        String fio = fio(text);
        String surname = fio == null ? null : fio.split(" ")[0];
        // одно-единственное слово — ещё не ФИО
        if (fio != null && fio.split(" ").length < 2) {
            fio = null;
        }
        return new Data(fio, surname, birthDate(text), policy(text), phone(text));
    }

    // ------------------------------------------------------------------
    // Дата рождения
    // ------------------------------------------------------------------

    /** Дата рождения из реплики. */
    static LocalDate birthDate(String text) {
        // 1) цифрами: 05.05.1962, 12.03.78, 5 мая 1962 не разбираем — словами ниже
        Matcher m = Pattern.compile("(\\d{1,2})[.\\-/](\\d{1,2})(?:[.\\-/](\\d{2,4}))?").matcher(text);
        while (m.find()) {
            int day = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            int year = m.group(3) == null ? -1 : normalizeYear(Integer.parseInt(m.group(3)));
            LocalDate d = build(day, month, year);
            if (d != null) {
                return d;
            }
        }
        // 2) словами с названием месяца: «двенадцатое марта семьдесят восьмого»
        String[] tokens = text.replaceAll("[^\\p{L}\\s]", " ").split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            int day = RussianTime.parseOrdinal(tokens[i]);
            if (day < 1 || day > 31) {
                continue;
            }
            for (int j = i + 1; j < Math.min(tokens.length, i + 4); j++) {
                LocalDate d = withMonth(day, tokens[j]);
                if (d == null) {
                    continue;
                }
                return build(day, d.getMonthValue(), yearAfter(tokens, j));
            }
        }
        // 3) словами с номером месяца: «двенадцатое три семьдесят восьмого».
        // Так пациент на диктофоне произносит «12.03.78», и месяц числом —
        // самый частый вариант после «двенадцатого марта».
        for (int i = 0; i < tokens.length; i++) {
            int day = RussianTime.parseOrdinal(tokens[i]);
            if (day < 1 || day > 31) {
                continue;
            }
            // месяц идёт следующим словом, но с возможным «ноль» перед ним:
            // «двенадцатое ноль три семьдесят восемь»
            int month = -1;
            int monthAt = -1;
            for (int k = i + 1; k < Math.min(tokens.length, i + 4); k++) {
                int v = RussianTime.parseNumber(tokens[k]);
                if (v >= 1 && v <= 12) {
                    month = v;
                    monthAt = k;
                    break;
                }
            }
            if (month < 0) {
                continue;
            }
            LocalDate d = build(day, month, yearAfter(tokens, monthAt));
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    /**
     * Год после названия месяца. Часто он назван ДВУМЯ словами: «семьдесят восьмого».
     * Раньше бралось только первое слово, и «78» превращалось в 1970.
     */
    private static int yearAfter(String[] tokens, int monthIndex) {
        int collected = -1;
        for (int k = monthIndex + 1; k < Math.min(tokens.length, monthIndex + 3); k++) {
            int v = RussianTime.parseNumber(tokens[k]);
            if (v < 0) {
                break;              // год закончился: дальше не про год
            }
            if (v >= 100 && collected < 0) {
                return v;           // «тысяча девятьсот...» — год назван целиком
            }
            // «семьдесят» + «восьмого» = 78. Брать только первое слово нельзя:
            // из «семьдесят» получался 1970 год вместо 1978.
            collected = collected < 0 ? v : collected + v;
        }
        // Двузначный год начинается с 10. Порог 31 здесь был ошибкой:
        // «двадцать шестого» давало 26 и год терялся.
        return collected >= 10 ? normalizeYear(collected) : -1;
    }

    private static LocalDate withMonth(int day, String token) {
        LocalDate probe = build(day, 1, 2000);
        if (probe == null) {
            return null;
        }
        try {
            return LocalDate.of(2000, monthOf(token), day);
        } catch (Exception e) {
            return null;
        }
    }

    private static int monthOf(String token) {
        String t = token.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}]", "");
        int exact = RussianTime.monthNumber(t);
        if (exact > 0) {
            return exact;
        }
        // усечённые формы: «мар» -> март
        for (String m : RussianTime.monthNames()) {
            if (t.length() >= 3 && m.startsWith(t)) {
                return RussianTime.monthNumber(m);
            }
        }
        return -1;
    }

    /**
     * Двузначный год восстанавливается с оглядкой на возраст (Приложение А.3):
     * пациенту не может быть больше 110 лет, поэтому «78» — это 1978, а не 2078.
     */
    public static int normalizeYear(int y) {
        if (y >= 100) {
            return y;
        }
        int current = LocalDate.now().getYear() % 100;
        return y > current ? 1900 + y : 2000 + y;
    }

    private static LocalDate build(int day, int month, int year) {
        try {
            int y = year > 0 ? year : 1900;
            LocalDate d = LocalDate.of(y, month, day);
            // без года возвращаем null: дата рождения без года не годится
            return year > 0 && d.getYear() > 1900 ? d : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Полис и телефон
    // ------------------------------------------------------------------

    /** Номер полиса ОМС без пробелов. */
    static String policy(String text) {
        Matcher m = POLICY.matcher(text);
        if (!m.find()) {
            return null;
        }
        String digits = m.group(1).replaceAll("[^0-9]", "");
        return digits.length() >= 15 && digits.length() <= 16 ? digits : null;
    }

    /** Телефон в тексте — тот же полис не считаем телефоном. */
    static String phone(String text) {
        Matcher m = Pattern.compile("\\+?7?[\\s-]?\\d{3}[\\s-]?\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{2}").matcher(text);
        if (!m.find()) {
            return null;
        }
        String digits = m.group().replaceAll("[^0-9]", "");
        if (digits.length() != 11) {
            return null;
        }
        return "+7" + digits.substring(digits.length() - 10);
    }
}