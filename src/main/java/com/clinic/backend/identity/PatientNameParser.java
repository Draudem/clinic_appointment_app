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

    /**
     * Дата рождения из реплики.
     *
     * <p>К прежней версии две претензии, обе проверены на фразах из живого звонка:
     * <ul>
     *   <li>токены нарезались регуляркой «не буква и не пробел», а цифры — это
     *       тоже «не буква». Год, названный цифрами («12 марта 1978»), выпадал из
     *       фразы целиком, и дата не находилась вообще;</li>
     *   <li>день и месяц искались строго в порядке «сначала день, потом месяц».
     *       Фраза «марта двенадцатое» уходила в другую ветку разбора и давала
     *       12 СЕНТЯБРЯ — молчаливо неверную дату рождения. Для идентификации
     *       это хуже, чем «не понял»: пациента можно опознать как другого человека.</li>
     * </ul>
     * Теперь цифры в токенах сохраняются, а день и месяц ищутся в любом порядке.
     */
    static LocalDate birthDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        // 1) цифрами с разделителями: 05.05.1962, 12.03.78
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
        // 2) словами: «двенадцатое марта тысяча девятьсот семьдесят восьмого»,
        //    «двенадцатое три семьдесят восемь», «12 марта 1978».
        String[] tokens = text.replaceAll("[^\\p{L}\\d]+", " ").trim().split("\\s+");
        for (int mAt = 0; mAt < tokens.length; mAt++) {
            int month = monthOrNumber(tokens[mAt]);
            if (month < 1 || month > 12) {
                continue;
            }
            // день ищем рядом в обе стороны: «марта двенадцатое» пациент
            // произносит так же легко, как «двенадцатое марта»
            for (int dAt = mAt - 2; dAt <= mAt + 2; dAt++) {
                if (dAt < 0 || dAt >= tokens.length || dAt == mAt) {
                    continue;
                }
                int day = dayAt(tokens, dAt);
                if (day < 1 || day > 31) {
                    continue;
                }
                // год ищем после последнего из «день/месяц»
                LocalDate d = build(day, month, yearAfter(tokens, Math.max(dAt, mAt)));
                if (d != null) {
                    return d;
                }
            }
        }
        return null;
    }

    /**
     * Месяц как название («марта», «марте») или как число — цифрами («03»)
     * либо прописью («три»).
     *
     * <p>Прописью месяц принимается только в форме именительного падежа:
     * «двенадцатое три» — это 12 марта, где «двенадцатое» день, а «три» месяц.
     * Порядковые формы («двенадцатое», «третий») отбрасываются, иначе день
     * принимался бы за месяц и дата собиралась бы неправильная.
     */
    private static int monthOrNumber(String token) {
        int byName = monthOf(token);
        if (byName > 0) {
            return byName;
        }
        String digits = token.replaceAll("\\D", "");
        if (!digits.isEmpty()) {
            try {
                int v = Integer.parseInt(digits);
                return v >= 1 && v <= 12 ? v : -1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        String w = token.toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (String ending : ORDINAL_ENDINGS) {
            if (w.endsWith(ending)) {
                return -1;
            }
        }
        int spoken = RussianTime.parseNumber(w);
        return spoken >= 1 && spoken <= 12 ? spoken : -1;
    }

    /** Окончания порядковых числительных — по ним отсекаем день от месяца. */
    private static final List<String> ORDINAL_ENDINGS =
            List.of("ое", "ой", "ый", "ий", "ого", "его", "ому", "ему", "ых", "их");

    /**
     * День в позиции, с учётом составного числительного.
     *
     * <p>«Тридцать первое» — один день, а не «тридцать» и «первое» рядом.
     * Без склейки бралось первое слово, и тридцать первое декабря превращалось
     * в тридцатое: дата вроде бы распознана, но на месяц раньше.
     */
    private static int dayAt(String[] tokens, int at) {
        int v = dayOrNumber(tokens[at]);
        if (v < 20 || v % 10 != 0 || at + 1 >= tokens.length) {
            return v;
        }
        String word = tokens[at].toLowerCase(Locale.ROOT).replace('ё', 'е');
        // ровно два десятка: «двадцать первое» = 21, «тридцать первое» = 31.
        // «тридцатого» — уже порядковое, его склеивать не с чем
        if (!word.equals("двадцать") && !word.equals("тридцать")) {
            return v;
        }
        int add = RussianTime.parseOrdinal(tokens[at + 1]);
        return add >= 1 && add <= 9 ? v + add : v;
    }

    /** День как порядковое числительное («двенадцатое») или как число («12»). */
    private static int dayOrNumber(String token) {
        int ordinal = RussianTime.parseOrdinal(token);
        if (ordinal > 0) {
            return ordinal;
        }
        String digits = token.replaceAll("\\D", "");
        if (!digits.isEmpty()) {
            try {
                return Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Год после названия месяца. Часто он назван ДВУМЯ словами: «семьдесят восьмого».
     * Раньше бралось только первое слово, и «78» превращалось в 1970.
     */
    /**
     * Год после названного дня/месяца.
     *
     * <p>Окно — до четырёх слов, а не до двух: год, названный словами,
     * состоит из четырёх частей («тысяча девятьсот семьдесят восьмого»), и
     * раньше окно обрывалось на третьем слове. Слово «тысяча» при этом
     * читалось как 1000, цикл видел «число >= 100» и возвращал 1000 —
     * год не получался, и сценарий просил дату рождения заново, сколько бы
     * раз её ни называли.
     */
    private static int yearAfter(String[] tokens, int monthIndex) {
        int collected = -1;
        // «тысяча девятьсот семьдесят восьмого» и «две тысячи пятого»:
        // тысячи не складываются с остатком, а умножаются — «две тысячи» это 2000,
        // а 2 + 1000 = 1002, откуда рождался несуществующий год.
        int thousands = 0;
        for (int k = monthIndex + 1; k < Math.min(tokens.length, monthIndex + 5); k++) {
            int v = RussianTime.parseNumber(tokens[k]);
            if (v < 0) {
                break;              // год закончился: дальше не про год
            }
            if (v == 1000) {
                thousands = collected < 0 ? 1 : collected;
                collected = 0;
                continue;
            }
            // «семьдесят» + «восьмого» = 78. Брать только первое слово нельзя:
            // из «семьдесят» получался 1970 год вместо 1978.
            collected = collected < 0 ? v : collected + v;
            if (thousands == 0 && collected > 2100) {
                return -1;         // такого года не бывает — дальше не гадаем
            }
        }
        if (thousands > 0) {
            int year = thousands * 1000 + Math.max(collected, 0);
            return year > 1900 && year <= 2100 ? year : -1;
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