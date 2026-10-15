package com.clinic.backend.voice;

import com.clinic.backend.doctor.Doctor;
import com.clinic.backend.doctor.DoctorRepository;
import com.clinic.backend.rag.ClinicalKnowledgeBase;
import com.clinic.backend.schedule.ScheduleEntry;
import com.clinic.backend.schedule.ScheduleProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * С-07 «Справка о графике работы».
 *
 * Все формулировки взяты из регламента РГ-ТЕЛ-01, а не придуманы:
 *   §6.1 — скрипты «Как вы работаете?», «В каком кабинете принимает врач?»;
 *   карточка С-07 — триггеры, ветвления «врач не найден», «отпуск»,
 *           результат «Сведения сообщены; предложена запись».
 * Если конкретного текста в регламенте нет, в {@link Reply} поле
 * {@code rule} остаётся пометкой «своя формулировка» — это видно в логах
 * и на /api/voice/parser, и такие места можно потом переписать дословно.
 *
 * Идентификация для С-07 регламентом НЕ требуется (карточка С-07,
 * «Идентификация: не требуется»), поэтому сервис работает по данным,
 * которые не привязаны к конкретному пациенту.
 *
 * ГРАНИЦА ДАННЫХ. Здесь нельзя додумывать. В базе нет режима работы
 * поликлиники как справочника МИС, нет адреса, нет праздничного приказа.
 * Для режима работы регламент всё же приводит значения в §2.2 — они и
 * произносятся, но помечены как условные (в тексте регламента прямо сказано:
 * «все значения ниже условные», источник истины — МИС). Для праздников
 * и адреса данных нет вовсе — там перевод оператору (§3.8).
 */
@Service
public class ScheduleQueryService {

    /**
     * Режим работы поликлиники. Регламент §2.2, скрипт §6.1.
     * Значения условные: при подключении МИС их надо читать из справочника
     * режима работы, а не отсюда.
     */
    private static final String WORKING_HOURS = "Поликлиника работает по будням "
            + "с семи тридцати до двадцати, в субботу с девяти до пятнадцати — "
            + "принимают дежурные терапевт и педиатр. В воскресенье выходной.";

    /** Праздничный режим — приказ главного врача, в МИС, у нас его нет. */
    private static final String HOLIDAYS = "Режим работы в праздничные дни устанавливается "
            + "приказом главного врача. Уточнит регистратор. Соединяю?";

    /** Адреса нет ни в doctors, ни в справочниках, которые у нас подключены. */
    private static final String ADDRESS = "Уточню адрес. Соединяю с регистратором?";

    /** Регламент, карточка С-07: результат — «Сведения сообщены; предложена запись». */
    private static final String OFFER_BOOKING = " Записать вас?";

    /** Регламент, карточка С-07: врач не найден. */
    private static final String NOT_FOUND = "Такого врача в расписании нет. "
            + "Могу записать к другому врачу этой специальности.";

    /** Регламент, пример диалога: отказ от предложенной записи. */
    public static final String BYE = "Хорошо. Всего доброго!";

    /** Дни недели в нумерации doctors.days (JavaScript getDay: 0=вс). */
    private static final String[] DAY_SHORT = {
            "в воскресенье", "в понедельник", "во вторник", "в среду",
            "в четверг", "в пятницу", "в субботу"};

    /**
     * Дни недели в предложном падеже: «по средам, пятницам».
     * В винительном выходило «по в среду» — так не говорят.
     */
    private static final String[] DAY_PREP = {
            "воскресеньям", "понедельникам", "вторникам", "средам",
            "четвергам", "пятницам", "субботам"};

    /** Отдельный порт не нужен: график врача и слоты лежат рядом, но это разные вещи. */
    private final DoctorRepository doctors;
    private final ScheduleProvider schedule;

    public ScheduleQueryService(DoctorRepository doctors, ScheduleProvider schedule) {
        this.doctors = doctors;
        this.schedule = schedule;
    }

    /**
     * Ответ на разобранный вопрос о графике.
     *
     * @param text      что сказать пациенту
     * @param transfer  нужен ли перевод оператору
     * @param specialty если удалось определить — специальность, чтобы после
     *                  согласия продолжить запись без лишнего вопроса
     * @param rule      отсылка к регламенту; «своя» означает формулировку,
     *                  которой в регламенте нет
     */
    public record Reply(String text, boolean transfer, String specialty, String rule) {
    }

    /** Разбор вопроса и ответ. Пустой Optional — отвечать не на что. */
    public Optional<Reply> answer(ScheduleInfoRule.ScheduleInfo info) {
        String q = ClinicalKnowledgeBase.normalize(info.text());

        // --- данные, которых у нас нет: только перевод оператору (§3.8) ---
        if (hasStem(q, "праздник")) {
            return Optional.of(new Reply(HOLIDAYS, true, null, "С-07/§2.2 нет приказа"));
        }
        if (hasStem(q, "адрес") || hasStem(q, "проехать") || hasStem(q, "найти")) {
            return Optional.of(new Reply(ADDRESS, true, null, "С-07/адреса нет в базе"));
        }
        if (hasStem(q, "отпуск") || hasStem(q, "больнич")) {
            // причина отсутствия называть нельзя (регламент), а заменяющего
            // врача у нас в базе нет — переводим оператору
            return Optional.of(new Reply("Уточню график работы врача. Соединяю?",
                    true, null, "С-07/отпусков нет в базе"));
        }

        List<Doctor> all = doctors.findAll();

        // --- вопрос про кабинет: ответ есть в слотах расписания (§6.1) ---
        if (info.location()) {
            return Optional.of(answerCabinet(q, all, info.specialty()));
        }

        if (all.isEmpty()) {
            return Optional.of(new Reply("Уточню график работы. Соединяю?",
                    true, null, "С-07/БД пуста"));
        }

        // --- врач назван по фамилии: самый частый и самый точный случай ---
        Optional<Doctor> named = findBySurname(q, all);
        if (named.isEmpty() && info.specialty() != null) {
            List<Doctor> bySpec = all.stream()
                    .filter(d -> info.specialty().equalsIgnoreCase(d.getSpecialty()))
                    .toList();
            if (bySpec.isEmpty()) {
                return Optional.of(new Reply(NOT_FOUND, false, info.specialty(), "С-07/не найден"));
            }
            return Optional.of(new Reply(describe(bySpec) + OFFER_BOOKING, false,
                    bySpec.get(0).getSpecialty(), "С-07/Ф1"));
        }
        if (named.isPresent()) {
            return Optional.of(new Reply(describe(named.get()) + OFFER_BOOKING, false,
                    named.get().getSpecialty(), "С-07/Ф1"));
        }
        // пациент назвал конкретного врача, а такого в расписании нет
        if (info.doctorHint() != null) {
            return Optional.of(new Reply(NOT_FOUND, false, null, "С-07/врач не найден"));
        }

        // --- «работает ли сегодня хирург» ---
        if (info.today() || info.tomorrow()) {
            LocalDate when = info.tomorrow() ? LocalDate.now().plusDays(1) : LocalDate.now();
            List<String> onDuty = all.stream().filter(d -> d.worksOn(when))
                    .map(d -> d.getFio() + " — " + d.getSpecialty()).toList();
            if (onDuty.isEmpty()) {
                return Optional.of(new Reply(cap(when == LocalDate.now() ? "сегодня" : "завтра")
                        + " приёма нет.", false, null, "С-07/Ф3"));
            }
            return Optional.of(new Reply(cap(when == LocalDate.now() ? "сегодня" : "завтра")
                    + " принимают: " + String.join("; ", onDuty) + "." + OFFER_BOOKING,
                    false, null, "С-07/Ф3"));
        }

        // --- назван день недели ---
        if (info.dayStem() != null) {
            int js = stemToJsDay(info.dayStem());
            List<String> onDuty = all.stream()
                    .filter(d -> d.getDaysList().contains(js))
                    .map(d -> d.getFio() + " — " + d.getSpecialty()).toList();
            if (onDuty.isEmpty()) {
                return Optional.of(new Reply("В этот день приёма нет.",
                        false, null, "С-07/Ф3"));
            }
            return Optional.of(new Reply(cap(DAY_SHORT[js]) + " принимают: "
                    + String.join("; ", onDuty) + "." + OFFER_BOOKING, false, null, "С-07/Ф3"));
        }

        // --- «как вы работаете», «до скольки», «работаете в субботу» (§6.1) ---
        return Optional.of(new Reply(WORKING_HOURS + OFFER_BOOKING, false, null,
                "С-07/§6.1 (значения условные)"));
    }

    /**
     * Ответ про кабинет. Номер кабинета есть в расписании (строка слота),
     * этажа в базе нет — регламентный скрипт «[этаж] этаж» поэтому не
     * произносится целиком: лучше недоговорить, чем назвать несуществующий
     * этаж.
     */
    private Reply answerCabinet(String q, List<Doctor> all, String specialty) {
        Optional<Doctor> named = findBySurname(q, all);
        if (named.isEmpty() && specialty != null) {
            named = all.stream()
                    .filter(d -> specialty.equalsIgnoreCase(d.getSpecialty()))
                    .findFirst();
        }
        if (named.isEmpty()) {
            return new Reply("Назову кабинет, когда вы назовёте врача.",
                    false, specialty, "С-07/кабинет, своя формулировка");
        }
        Doctor d = named.get();
        String cabinet = firstCabinet(d);
        if (cabinet == null) {
            return new Reply("Номер кабинета есть в SMS о записи.",
                    false, d.getSpecialty(), "С-07/§6.1");
        }
        return new Reply(d.getFio() + " принимает в кабинете "
                + RussianTime.number(Integer.parseInt(cabinet))
                + ". Номер кабинета есть и в SMS о записи." + OFFER_BOOKING,
                false, d.getSpecialty(), "С-07/§6.1");
    }

    /** Номер кабинета из ближайшего слота врача, либо null. */
    private String firstCabinet(Doctor d) {
        try {
            List<ScheduleEntry> entries = schedule.byDoctorFio(d.getFio(),
                    LocalDate.now(), LocalDate.now().plusDays(14));
            for (ScheduleEntry e : entries) {
                if (e.cabinet() != null) {
                    return String.valueOf(e.cabinet());
                }
            }
        } catch (RuntimeException e) {
            // источник расписания недоступен — просто не отвечаем про кабинет
            return null;
        }
        return null;
    }

    /**
     * Фраза про врача: имя, специальность, дни и время из строки doctors.
     * Формулировка своя — регламент для этого случая скрипта не даёт,
     * но выдержана по его образцу («Волков — с восьми до четырнадцати,
     * кабинет сто двенадцать»).
     */
    private static String describe(Doctor d) {
        return "По специальности «" + d.getSpecialty() + "» принимает "
                + d.getFio() + " — " + days(d) + ", " + time(d.getTime()) + ".";
    }

    private static String describe(List<Doctor> ds) {
        List<String> parts = new ArrayList<>();
        for (Doctor d : ds) {
            parts.add(d.getFio() + " — " + days(d) + ", " + time(d.getTime()));
        }
        return "Принимают: " + String.join("; ", parts) + ".";
    }

    private static String days(Doctor d) {
        List<Integer> js = d.getDaysList();
        boolean allWeekdays = js.containsAll(List.of(1, 2, 3, 4, 5));
        if (allWeekdays) {
            // формулировка регламента из карточки С-07: «Хирурги принимают
            // каждый будний день»
            return "каждый будний день";
        }
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            if (js.contains(i)) {
                names.add(DAY_PREP[i]);
            }
        }
        if (js.contains(6)) {
            names.add(DAY_PREP[6]);
        }
        if (js.contains(0)) {
            names.add(DAY_PREP[0]);
        }
        return names.isEmpty() ? "дни уточняются у регистратора" : "по " + join(names);
    }

    /** «средам, пятницам и четвергам» — с «и» перед последним, как говорят. */
    private static String join(List<String> parts) {
        if (parts.size() <= 1) {
            return String.join("", parts);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1))
                + " и " + parts.get(parts.size() - 1);
    }

    private static String time(String hhmm) {
        return hhmm == null || hhmm.isBlank()
                ? "время приёма уточняется у регистратора"
                : RussianTime.sayTime(hhmm);
    }

    /**
     * Поиск врача по фамилии в любом падеже: «Кузнецова», «к Соколову».
     * Только префикс слова и только если пациент обр��тился к поликлинике
     * без глагола записи — иначе «хочу записаться к Соколову» превратилось бы
     * в справку вместо записи.
     */
    private static Optional<Doctor> findBySurname(String q, List<Doctor> all) {
        for (Doctor d : all) {
            String surname = d.getFio().split("\\s+")[0].toLowerCase(Locale.ROOT);
            if (surname.isBlank()) {
                continue;
            }
            for (String word : q.split("[^а-яa-z]+")) {
                if (word.startsWith(surname) && word.length() >= surname.length()) {
                    return Optional.of(d);
                }
            }
        }
        return Optional.empty();
    }

    private static int stemToJsDay(String stem) {
        return switch (stem) {
            case "понедельник" -> 1;
            case "вторник" -> 2;
            case "среда", "сред" -> 3;
            case "четверг" -> 4;
            case "пятниц" -> 5;
            case "суббот" -> 6;
            default -> 0;
        };
    }

    private static boolean hasStem(String q, String stem) {
        return ScheduleInfoRule.hasStem(q, stem);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
