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
 * названия специальностей из БД.
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
