package com.clinic.backend.rag;

import com.clinic.backend.doctor.Doctor;
import com.clinic.backend.doctor.DoctorRepository;
import com.clinic.backend.specialty.Specialty;
import com.clinic.backend.specialty.SpecialtyRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * База знаний клиники для RAG. Источники:
 *  1) БД (специальности с болезнями, врачи с расписанием) — «живая» часть;
 *  2) словарь разговорных синонимов — закрывает ровно те случаи, на которых
 *     ломается нейросетевая NLU-модель (дантист, окулист, невропатолог,
 *     «зуб стреляет», «кожник» и т.п.).
 *
 * Здесь же — отображение токенов NLU-модели (<DENTIST>, <ENT>, ...) в реальные
 * названия специальностей из БД и группировка специальностей («Стоматолог»
 * в модели → «Стоматолог-терапевт», «Стоматолог-хирург», … в БД).
 */
@Component
public class ClinicalKnowledgeBase {

    private final SpecialtyRepository specialties;
    private final DoctorRepository doctors;

    public ClinicalKnowledgeBase(SpecialtyRepository specialties, DoctorRepository doctors) {
        this.specialties = specialties;
        this.doctors = doctors;
    }

    /** Токен NLU-модели -> название специальности в БД. */
    public static final Map<String, String> NLU_TOKEN_TO_SPECIALTY = Map.ofEntries(
            Map.entry("GP", "Терапевт"),
            Map.entry("DENTIST", "Стоматолог"),
            Map.entry("DERMATOLOGIST", "Дерматолог"),
            Map.entry("GYNECOLOGIST", "Гинеколог"),
            Map.entry("OPHTHALMOLOGIST", "Офтальмолог"),
            Map.entry("NEUROLOGIST", "Невролог"),
            Map.entry("PSYCHOLOGIST", "Психолог"),
            Map.entry("SURGEON", "Хирург"),
            Map.entry("ENT", "ЛОР"));

    /**
     * Псевдоспециальности модели -> реальные строки doctors.specialty.
     *
     * <p>Модель возвращает обобщённые токены: «Стоматолог», «Гинеколог», «ЛОР».
     * В БД специальности детальнее: «Стоматолог-терапевт», «Акушер-гинеколог»,
     * «Лор (отоларинголог)». Если искать точное совпадение, запись к стоматологу
     * и гинекологу становится невозможной (что и произошло на живом звонке:
     * «Приёма по специальности "Стоматолог" сейчас нет»).
     *
     * <p>Здесь — единственное место, где это сопоставление описано явно.
     * Эвристики startsWith/contains не годятся: contains("Терапевт") нашёл бы
     * «Стоматолог-терапевт», и пациент, попросивший терапевта, попал бы к стоматологу.
     */
    public static final Map<String, List<String>> SPECIALTY_GROUPS = Map.ofEntries(
            Map.entry("Стоматолог", List.of(
                    "Стоматолог-терапевт", "Стоматолог-хирург", "Стоматолог-ортопед",
                    "Детский стоматолог", "Ортодонт", "Пародонтолог")),
            Map.entry("Гинеколог", List.of(
                    "Акушер-гинеколог", "Гинеколог-эндокринолог",
                    "Репродуктолог", "Маммолог")),
            Map.entry("ЛОР", List.of(
                    "Лор (отоларинголог)", "Детский отоларинголог")),
            Map.entry("Педиатр", List.of(
                    "Педиатр", "Неонатолог")),
            Map.entry("Хирург", List.of(
                    "Хирург", "Детский хирург", "Стоматолог-хирург")),
            Map.entry("Невролог", List.of(
                    "Невролог", "Детский невролог")),
            Map.entry("Офтальмолог", List.of(
                    "Офтальмолог", "Детский офтальмолог")),
            Map.entry("Эндокринолог", List.of(
                    "Эндокринолог", "Детский эндокринолог")),
            Map.entry("Кардиолог", List.of(
                    "Кардиолог", "Детский кардиолог")),
            Map.entry("Ортопед", List.of(
                    "Ортопед", "Детский ортопед-травматолог", "Травматолог")),
            Map.entry("Уролог", List.of(
                    "Уролог", "Детский уролог-андролог")));

    /**
     * Все реальные строки doctors.specialty для запрошенной специальности.
     *
     * <p>«Стоматолог» → 6 подспециальностей; «Терапевт» → только «Терапевт»
     * (без «Стоматолог-терапевт»). Если специальность не входит в группы,
     * возвращается она сама — чтобы поиск по точному названию из БД работал
     * без дополнительных правил.
     */
    public static List<String> resolveSpecialties(String specialty) {
        if (specialty == null || specialty.isBlank()) {
            return List.of();
        }
        return SPECIALTY_GROUPS.getOrDefault(specialty, List.of(specialty));
    }

    /**
     * Специальность (название в БД) -> набор разговорных формулировок.
     * Часть терминов взята из hold-out словаря ai_model (то, что модель путает).
     */
    public static final Map<String, List<String>> SYNONYMS = new LinkedHashMap<>();

    static {
        SYNONYMS.put("Терапевт", List.of("терапевт", "терапия", "простуда", "орви", "температура",
                "кашель", "насморк", "слабость", "общее недомогание", "бронхит", "гастрит", "анемия"));
        SYNONYMS.put("Кардиолог", List.of("кардиолог", "сердце", "сердечный", "давление", "гипертония",
                "аритмия", "боль в груди", "одышка", "инфаркт", "холестерин"));
        SYNONYMS.put("Невролог", List.of("невролог", "невропатолог", "нервы", "нервный", "голова",
                "головная боль", "мигрень", "спина", "спину", "поясница", "остеохондроз",
                "головокружение", "онемение", "бессонница", "эпилепсия"));
        SYNONYMS.put("Хирург", List.of("хирург", "грыжа", "аппендицит", "вены", "варикоз",
                "холецистит", "опухоль", "операция", "нарыв"));
        SYNONYMS.put("Офтальмолог", List.of("офтальмолог", "окулист", "глаза", "глаз", "зрение",
                "конъюнктивит", "близорукость", "глаукома", "катаракта", "косоглазие"));
        SYNONYMS.put("ЛОР", List.of("лор", "отоларинголог", "ухогорлонос", "ухо", "горло", "нос",
                "синусит", "отит", "тонзиллит", "ринит", "ангина", "гланды"));
        SYNONYMS.put("Дерматолог", List.of("дерматолог", "кожник", "кожа", "сыпь", "зуд",
                "дерматит", "псориаз", "экзема", "прыщи", "акне", "кандидоз"));
        SYNONYMS.put("Эндокринолог", List.of("эндокринолог", "щитовидка", "щитовидная", "гормоны",
                "диабет", "зоб", "ожирение", "лишний вес", "гипотиреоз"));
        SYNONYMS.put("Гинеколог", List.of("гинеколог", "гинекология", "женский врач", "цикл",
                "яичники", "миома", "эндометриоз", "беременность"));
        SYNONYMS.put("Уролог", List.of("уролог", "мочевой", "почки", "почка", "цистит",
                "простатит", "пиелонефрит", "мочекаменная"));
        SYNONYMS.put("Педиатр", List.of("педиатр", "ребёнок", "ребенок", "дети", "детский",
                "младенец", "малыш"));
        SYNONYMS.put("Ортопед", List.of("ортопед", "колено", "сустав", "кость", "перелом",
                "артроз", "сколиоз", "плоскостопие", "травма"));
        SYNONYMS.put("Стоматолог", List.of("стоматолог", "дантист", "зуб", "зубы", "зубной",
                "десна", "челюсть", "кариес"));
        SYNONYMS.put("Психолог", List.of("психолог", "психотерапевт", "тревога", "паника",
                "стресс", "депрессия", "выгорание"));
    }

    /** Русское название специальности по токену модели (или null). */
    public static String russianNameForNluToken(String token) {
        if (token == null) {
            return null;
        }
        return NLU_TOKEN_TO_SPECIALTY.get(token);
    }

    /**
     * Основы слов, которые однозначно называют ВРАЧА, а не симптом.
     *
     * Отличие от {@link #SYNONYMS} принципиальное: там есть и болезни
     * («кашель», «давление»), и здесь их быть не должно. Этот список
     * используется как предохранитель, когда модель отказалась понимать фразу,
     * и цена ошибки здесь — решить, что пациент хочет записаться. Лучше
     * не сработать, чем записать не туда.
     */
    public static final Map<String, List<String>> DESIGNATION_STEMS = new LinkedHashMap<>();

    static {
        DESIGNATION_STEMS.put("Терапевт", List.of("терапевт"));
        DESIGNATION_STEMS.put("Стоматолог", List.of("стоматолог", "дантист", "зубн"));
        DESIGNATION_STEMS.put("Офтальмолог", List.of("офтальмолог", "окулист", "овтальмолог",
                "оптальмолог", "апилинолог"));
        DESIGNATION_STEMS.put("ЛОР", List.of("лор", "отоларинголог"));
        DESIGNATION_STEMS.put("Невролог", List.of("невролог", "невропатолог"));
        DESIGNATION_STEMS.put("Хирург", List.of("хирург"));
        DESIGNATION_STEMS.put("Дерматолог", List.of("дерматолог", "кожник"));
        DESIGNATION_STEMS.put("Эндокринолог", List.of("эндокринолог"));
        DESIGNATION_STEMS.put("Гинеколог", List.of("гинеколог"));
        DESIGNATION_STEMS.put("Уролог", List.of("уролог"));
        DESIGNATION_STEMS.put("Педиатр", List.of("педиатр"));
        DESIGNATION_STEMS.put("Ортопед", List.of("ортопед"));
        DESIGNATION_STEMS.put("Психолог", List.of("психолог"));
        DESIGNATION_STEMS.put("Кардиолог", List.of("кардиолог"));
    }

    /** Длина окончания, которую ещё считаем тем же словом: «терапевтУ», «лорОМ». */
    private static final int SUFFIX_SLACK = 4;

    /**
     * Специальность, если пациент СЛОВОМ назвал врача: «к терапевту»,
     * «к лору», «к дантисту».
     *
     * Сравнение по началу слова, а не {@code contains} как в
     * {@link #recommendSpecialty}: там подстрока ищется где угодно внутри фразы
     * и специальность «Кардиолог» находится в слове «кардиологическое» или даже
     * в чужом слове про погоду. Здесь слово вырезается целиком, окончание вида
     * «-ому/а/ом» допускается, а хвост длиннее {@value #SUFFIX_SLACK} букв —
     * уже другое слово.
     *
     * Возвращает null, если врач не назван явно.
     */
    public static String specialtyByDesignation(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String q = normalize(text).replace('ё', 'е');
        for (String word : q.split("[^а-яa-z]+")) {
            if (word.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, List<String>> e : DESIGNATION_STEMS.entrySet()) {
                for (String stem : e.getValue()) {
                    if (word.length() >= stem.length()
                            && word.length() - stem.length() <= SUFFIX_SLACK
                            && word.startsWith(stem)) {
                        return e.getKey();
                    }
                }
            }
        }
        return null;
    }

    /** Все чанки базы знаний (БД + инструкции). */
    public List<KnowledgeChunk> chunks() {
        List<KnowledgeChunk> chunks = new ArrayList<>();

        for (Specialty s : specialties.findAll()) {
            Set<String> tags = new LinkedHashSet<>();
            tags.add(s.getName().toLowerCase(Locale.ROOT));
            tags.addAll(SYNONYMS.getOrDefault(s.getName(), List.of()));
            for (String d : s.getDiseases()) {
                tags.add(d.toLowerCase(Locale.ROOT));
            }
            String text = "Специальность: " + s.getName()
                    + ". Лечит: " + String.join(", ", s.getDiseases())
                    + ". Ключевые слова: " + String.join(", ", SYNONYMS.getOrDefault(s.getName(), List.of()));
            chunks.add(new KnowledgeChunk("specialty:" + s.getId(), "specialty", s.getName(),
                    text, new ArrayList<>(tags), 0));
        }

        for (Doctor d : doctors.findAllByOrderByFioAsc()) {
            List<String> tags = List.of(d.getFio().toLowerCase(Locale.ROOT),
                    d.getSpecialty().toLowerCase(Locale.ROOT));
            String text = "Врач: " + d.getFio() + ". Специальность: " + d.getSpecialty()
                    + ". Приём: " + d.getTime() + ".";
            chunks.add(new KnowledgeChunk("doctor:" + d.getId(), "doctor", d.getFio(),
                    text, tags, 0));
        }

        chunks.add(new KnowledgeChunk("instruction:booking", "instruction", "Как записаться",
                "Чтобы записаться, назовите специальность или симптом, желаемую дату и время. "
                        + "Я подберу врача и свободное время приёма.",
                List.of("записаться", "запись", "приём", "прием", "талон"), 0));
        chunks.add(new KnowledgeChunk("instruction:cancel", "instruction", "Как отменить",
                "Отменить запись можно командой «отмените запись»: назовите врача и дату.",
                List.of("отменить", "отмена", "перенести", "перенос"), 0));
        chunks.add(new KnowledgeChunk("instruction:operator", "instruction", "Оператор",
                "Если вопрос не про запись к врачу, я передам вас оператору.",
                List.of("оператор", "человек", "администратор"), 0));

        return chunks;
    }

    /**
     * Лучшая специальность для произвольного текста (лемматизации нет — идём
     * по вхождению ключевых слов). null, если ничего не подошло.
     */
    public String recommendSpecialty(String query) {
        String q = normalize(query);
        if (q.isBlank()) {
            return null;
        }
        String best = null;
        int bestScore = 0;
        for (Map.Entry<String, List<String>> e : SYNONYMS.entrySet()) {
            int score = 0;
            for (String syn : e.getValue()) {
                if (q.contains(syn)) {
                    // более длинное совпадение весомее
                    score += syn.length();
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = e.getKey();
            }
        }
        return best;
    }

    public static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace('ё', 'е').trim();
    }
}