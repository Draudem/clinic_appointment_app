package com.clinic.backend.voice;

import com.clinic.backend.appointment.AppointmentService;
import com.clinic.backend.identity.PatientIdentity;
import com.clinic.backend.rag.ClinicalKnowledgeBase;
import com.clinic.backend.identity.PatientIdentityResolver;
import com.clinic.backend.identity.PatientNameParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Сценарий С-01 «Запись на приём» по регламенту РГ-ТЕЛ-01.
 *
 * ПОРЯДОК ШАГОВ (раздел 5, С-01):
 *   1. сквозные правила — красные флаги, просьба человека, жалоба, медвопрос;
 *   2. лестница непонимания R-05;
 *   3. идентификация пациента (3.5) — без неё запись не выполняется;
 *   4. проверка дубликата: есть активная запись на эту специальность —
 *      предложить перенос (переход в С-03);
 *   5. подбор и предложение слотов (не более трёх);
 *   6. выбор слота и подтверждение вслух;
 *   7. создание записи и SMS.
 *
 * Сценарий НЕ разбирает реплику сам: интент и специальность приходят от
 * {@link CommandParser}, время — от {@link TimeResolver}. Замена любого из
 * этих этапов на заглушку или на более сильную модель не меняет здесь ничего.
 */
@Service
public class BookingFlow {

    private static final Logger log = LoggerFactory.getLogger(BookingFlow.class);

    /** Регламент 3.2: не более трёх вариантов за раз. */
    private static final int MAX_OPTIONS = 3;

    private final CommandParser parser;
    private final SlotResolver slots;
    private final AppointmentService appointments;
    private final PatientIdentityResolver identity;
    private final DialogueSessionRepository sessions;
    private final ScheduleQueryService scheduleQueries;

    public BookingFlow(CommandParser parser, SlotResolver slots, AppointmentService appointments,
                       PatientIdentityResolver identity, DialogueSessionRepository sessions,
                       ScheduleQueryService scheduleQueries) {
        this.parser = parser;
        this.slots = slots;
        this.appointments = appointments;
        this.identity = identity;
        this.sessions = sessions;
        this.scheduleQueries = scheduleQueries;
    }

    @Transactional
    public Turn handle(DialogueSession session, PatientIdentity who, String text) {
        // --- 1. Сквозные правила: проверяются на каждой реплике ---
        PolicyRules.Verdict verdict = PolicyRules.check(text);
        if (PolicyRules.isRecordingOptOut(text)) {
            session.setRecordingOptOut(true);
            session.append("система", "пациент отказался от записи разговора");
        }
        if (verdict.blocksScenario()) {
            session.resetMisses();
            if (verdict.decision() == PolicyRules.Decision.TRANSFER) {
                session.setHandedOff(true);
                session.setStage("OPERATOR");
            } else {
                session.setStage("CONFIRMED");
            }
            session.append("ассистент", verdict.reply());
            return new Turn(verdict.reply(),
                    verdict.decision() == PolicyRules.Decision.TRANSFER
                            ? Turn.Action.TRANSFER : Turn.Action.HANGUP,
                    session.getStage(), List.of(), verdict.rule(), verdict.resultCode(), 0);
        }

        // --- 2. Неразборчивая реплика: лестница R-05 ---
        // На этапах подтверждения ответ «да» вовсе не похож на речь,
        // и проверять его на «шум» бессмысленно.
        if (!PolicyRules.expectsYesNo(session.getStage()) && PolicyRules.isGarbage(text)) {
            session.increaseMisses();
            int misses = session.getMissCount();
            if (PolicyRules.shouldTransferAfterMisses(misses)) {
                return transfer(session, PolicyRules.misunderstandingReply(misses), "R-05");
            }
            String reply = PolicyRules.misunderstandingReply(misses);
            session.append("ассистент", reply);
            return Turn.continueWith(reply, session.getStage(), List.of(), misses);
        }

        session.resetMisses();
        session.append("пациент", text);
        session.incrementExchanges();

        // --- 3. Обработка по этапу ---
        return switch (session.getStage() == null ? "SPECIALTY" : session.getStage()) {
            case "PENDING_CONFIRMATION" -> onConfirm(session, text);
            case "OFFER" -> onOfferChoice(session, text);
            case "IDENTITY" -> onIdentity(session, who, text);
            case "RESCHEDULE" -> onReschedule(session, who, text);
            case "DUP" -> onDuplicate(session, text);
            case "SCHEDULE_OFFER" -> onScheduleOffer(session, who, text);
            default -> onIntent(session, who, text);
        };
    }

    // ------------------------------------------------------------------
    // С-07. Справка о графике работы
    // ------------------------------------------------------------------

    /**
     * Ответ на вопрос о графике.
     *
     * Регламент требует для С-07 ровно двух вещей: сообщить сведения
     * (INFO_GIVEN) и предложить запись. Поэтому почти на каждый ответ
     * добавляется «Записать вас?», а этап переходит в SCHEDULE_OFFER —
     * ждёт ответа. На «нет» произносится прощание из примера диалога,
     * на «да» запись продолжается с уже названной специальностью.
     *
     * Идентификация на этом шаге не выполняется: карточка С-07 в регламенте
     * прямо говорит «Идентификация: не требуется».
     */
    private Turn onScheduleInfo(DialogueSession session, PatientIdentity who,
                                ScheduleInfoRule.ScheduleInfo info) {
        Optional<ScheduleQueryService.Reply> answer = scheduleQueries.answer(info);
        if (answer.isEmpty()) {
            // отвечать не на что — разбираем фразу обычным порядком
            return onIntent(session, who, info.text());
        }
        ScheduleQueryService.Reply reply = answer.get();

        if (reply.transfer()) {
            return transfer(session, reply.text(), reply.rule());
        }
        if (reply.specialty() != null && !reply.specialty().isBlank()) {
            session.setSpecialty(reply.specialty());
        }
        session.setStage(reply.text().endsWith("Записать вас?") ? "SCHEDULE_OFFER" : "SPECIALTY");
        session.append("ассистент", reply.text());
        return Turn.continueWith(reply.text(), session.getStage(), List.of(), 0);
    }

    /** Ответ на предложение записи после справки о графике. */
    private Turn onScheduleOffer(DialogueSession session, PatientIdentity who, String text) {
        PolicyRules.Answer answer = PolicyRules.answer(text);
        session.setStage("SPECIALTY");
        if (answer == PolicyRules.Answer.YES) {
            String specialty = session.getSpecialty();
            if (specialty == null || specialty.isBlank()) {
                // специальность не была названа — обычный путь спросит её
                return onIntent(session, who, text);
            }
            // специальность уже известна, продолжаем запись с неё
            return afterSpecialty(session, who, null);
        }
        if (answer == PolicyRules.Answer.NO) {
            String bye = ScheduleQueryService.BYE;
            session.setStage("DONE");
            session.append("ассистент", bye);
            return Turn.continueWith(bye, "DONE", List.of(), 0);
        }
        // «да/нет» не понято — возвращаемся к вопросу о специальности
        return onIntent(session, who, text);
    }

    // ------------------------------------------------------------------
    // Шаг: понять намерение и специальность
    // ------------------------------------------------------------------

    private Turn onIntent(DialogueSession session, PatientIdentity who, String text) {
        // С-07 идёт ПЕРЕД моделью. Проверка нужна не для красоты: вопрос про
        // график — это правило, которое нельзя отдать на волю модели, потому
        // что и предыдущая, и новая версия на части таких фраз отвечали
        // <UNSUPPORTED> или, что хуже, <BOOK>. Спросили «работает ли сегодня
        // хирург» — пациент должен получить справку, а не попасть в запись.
        Optional<ScheduleInfoRule.ScheduleInfo> schedule = ScheduleInfoRule.detect(text);
        if (schedule.isPresent()) {
            return onScheduleInfo(session, who, schedule.get());
        }

        ParsedCommand parsed = parser.parse(text);
        session.setPendingFrame(parsed.frame());

        String specialty = parsed.hasSpecialty() ? parsed.specialty() : session.getSpecialty();
        if (specialty == null || specialty.isBlank()) {
            String reply = "К какому врачу хотите записаться? Назовите специальность "
                    + "или скажите, что беспокоит.";
            session.setStage("SPECIALTY");
            session.append("ассистент", reply);
            return Turn.continueWith(reply, "SPECIALTY", List.of(), 0);
        }
        session.setSpecialty(specialty);
        session.resetMisses();

        if (!slots.specialtyStaffed(specialty)) {
            String reply = "Приёма по специальности «" + specialty + "» сейчас нет. "
                    + "Могу предложить: " + String.join(", ", slots.availableSpecialties()) + ".";
            session.setStage("SPECIALTY");
            session.append("ассистент", reply);
            return Turn.continueWith(reply, "SPECIALTY", List.of(), 0);
        }

        // от «мне к стоматологу на пятницу» сразу запоминаем пожелание о времени
        rememberTime(session, parsed, text);

        // если пациент сразу назвал конкретный слот — сразу к подтверждению
        Optional<Turn.Slot> exact = findExact(session, specialty, parsed, text);

        return afterSpecialty(session, who, exact.orElse(null));
    }

    /** Запоминает дату/время, названные вместе со специальностью. */
    private void rememberTime(DialogueSession session, ParsedCommand parsed, String text) {
        LocalDate today = LocalDate.now();
        TimeResolver.TimePreference pref =
                TimeResolver.resolve(parsed.timeFactors(), text, today);
        session.setWantedDate(pref.date() == null ? null : pref.date().toString());
        session.setWantedTime(pref.exact());
    }

    /** Если пациент назвал конкретное время, подбираем именно его. */
    private Optional<Turn.Slot> findExact(DialogueSession session, String specialty,
                                          ParsedCommand parsed, String text) {
        LocalDate today = LocalDate.now();
        TimeResolver.TimePreference pref =
                TimeResolver.resolve(parsed.timeFactors(), text, today);
        if (pref.isEmpty()) {
            return Optional.empty();
        }
        return slots.findSlot(specialty, pref, today).map(this::toSlot);
    }

    // ------------------------------------------------------------------
    // Шаг: после того как специальность известна
    // ------------------------------------------------------------------

    private Turn afterSpecialty(DialogueSession session, PatientIdentity who,
                                Turn.Slot chosen) {
        // --- 3. Идентификация ---
        if (!who.identified()) {
            String reply = "Назовите, пожалуйста, фамилию, имя, отчество и дату рождения.";
            session.setStage("IDENTITY");
            session.append("ассистент", reply);
            return Turn.continueWith(reply, "IDENTITY", List.of(), 0);
        }
        session.setPatientFio(who.fio());
        session.setPatientBirthDate(who.birthDate() == null ? null : who.birthDate().toString());

        String greeting = who.addressName().isBlank() ? "" : "Спасибо, " + who.addressName() + ". ";

        // --- 4. Проверка дубликата ---
        Optional<Map<String, Object>> dup = activeBooking(who, session.getSpecialty());
        if (dup.isPresent()) {
            return offerMove(session, greeting, dup.get());
        }

        // --- конкретный слот сразу к подтверждению ---
        if (chosen != null) {
            return toConfirm(session, greeting + "Записываю: ", chosen);
        }

        // --- 5. Предложить слоты ---
        return offerSlots(session, greeting);
    }

    private Turn offerSlots(DialogueSession session, String greeting) {
        LocalDate today = LocalDate.now();
        TimeResolver.TimePreference pref = from(session);
        List<SlotResolver.Slot> found = slots.offerSlots(session.getSpecialty(), pref, today, MAX_OPTIONS);
        if (found.isEmpty()) {
            // С-11: нет слотов в горизонте — лист ожидания, а не выдуманная запись
            String reply = greeting + "Свободного времени в ближайшие две недели нет. "
                    + "Могу поставить вас в лист ожидания и перезвонить.";
            session.setStage("OFFER");
            session.setOfferedSlots("");
            session.append("ассистент", reply);
            return Turn.continueWith(reply, "OFFER", List.of(), 0);
        }
        List<Turn.Slot> options = found.stream().map(this::toSlot).toList();
        StringBuilder sb = new StringBuilder(greeting).append("Ближайшее время: ");
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(options.get(i).spokenCompact(today));
        }
        sb.append(". Что вам подходит?");
        String reply = sb.toString();
        session.setOfferedSlots(encode(options));
        session.setStage("OFFER");
        session.append("ассистент", reply);
        return Turn.continueWith(reply, "OFFER", options, 0);
    }

    // ------------------------------------------------------------------
    // Шаг: ответ на предложенные слоты
    // ------------------------------------------------------------------

    private Turn onOfferChoice(DialogueSession session, String text) {
        List<Turn.Slot> offered = decode(session.getOfferedSlots());
        if (offered.isEmpty()) {
            // Варианты ещё не предлагали: показать их, а не спрашивать «выберите из списка»
            return offerSlots(session, "");
        }
        Turn.Slot picked = pickSlot(offered, text);
        if (picked == null) {
            String reply = "Скажите, пожалуйста, когда вам удобно: можно назвать дату "
                    + "или выбрать один из предложенных вариантов.";
            session.append("ассистент", reply);
            return Turn.continueWith(reply, "OFFER", offered, 0);
        }
        return toConfirm(session, "", picked);
    }

    /**
     * Выбор слота из предложенных.
     *
     * Сначала по номеру («второй», «2»), затем по смыслу — по названной дате,
     * дню недели или времени. Номер важно проверять ПОСЛЕ смысла: слово
     * «вторник» означает день недели, а не «второй вариант».
     */
    Turn.Slot pickSlot(List<Turn.Slot> offered, String text) {
        if (offered.isEmpty() || text == null) {
            return null;
        }
        String q = ClinicalKnowledgeBase.normalize(text);

        // 1. названная дата или день недели
        LocalDate today = LocalDate.now();
        LocalDate saidDate = RussianTime.parseDayMonth(q, today);
        if (saidDate == null) {
            saidDate = RussianTime.parseNumericDate(q, today);
        }
        if (saidDate == null && q.contains("завтра")) {
            saidDate = today.plusDays(1);
        }
        if (saidDate == null && q.contains("послезавтра")) {
            saidDate = today.plusDays(2);
        }
        if (saidDate == null) {
            saidDate = byWeekday(q, today);
        }
        // 2. названное время
        String saidTime = null;
        java.time.LocalTime digits = RussianTime.parseHhmm(q);
        if (digits != null) {
            saidTime = HHMM.format(digits);
        } else if (looksLikeTime(q)) {
            // Без этого «второй» читался как «два часа» -> 02:00, и выбор варианта
            // по номеру переставал работать вовсе.
            java.time.LocalTime words = RussianTime.parseWordsTime(q);
            if (words != null) {
                saidTime = HHMM.format(words);
            }
        }

        if (saidDate != null || saidTime != null) {
            for (Turn.Slot s : offered) {
                boolean dateOk = saidDate == null || saidDate.equals(s.date());
                boolean timeOk = saidTime == null || saidTime.equals(s.time());
                if (dateOk && timeOk) {
                    return s;
                }
            }
        }
        // 3. порядковый номер — только если ничего не названо словами
        boolean namedSomething = saidDate != null || saidTime != null
                || containsWeekdayWord(q, today) || RussianTime.partOfDay(q) != null;
        if (!namedSomething) {
            Integer idx = PolicyRules.ordinalChoice(text);
            if (idx != null && idx >= 0 && idx < offered.size()) {
                return offered.get(idx);
            }
        }
        // 4. единственный вариант и ответ «да»
        if (offered.size() == 1 && PolicyRules.answer(text) == PolicyRules.Answer.YES) {
            return offered.get(0);
        }
        return null;
    }

    /** Явный признак того, что пациент назвал именно время, а не количество. */
    private static boolean looksLikeTime(String q) {
        for (String mark : new String[]{"час", "утра", "вечера", "дня", "ночи", "дня",
                "во сколько", "во сколько"}) {
            if (q.contains(mark)) {
                return true;
            }
        }
        return false;
    }

    private static final java.time.format.DateTimeFormatter HHMM =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm");

    private LocalDate byWeekday(String q, LocalDate today) {
        for (java.time.DayOfWeek d : java.time.DayOfWeek.values()) {
            String stem = switch (d) {
                case MONDAY -> "понедельник";
                case TUESDAY -> "вторник";
                case WEDNESDAY -> "сред";
                case THURSDAY -> "четверг";
                case FRIDAY -> "пятниц";
                case SATURDAY -> "суббот";
                case SUNDAY -> "воскресень";
            };
            if (q.contains(stem)) {
                return RussianTime.nextWeekday(today, d);
            }
        }
        return null;
    }

    private boolean containsWeekdayWord(String q, LocalDate today) {
        return byWeekday(q, today) != null;
    }

    // ------------------------------------------------------------------
    // Шаг: подтверждение
    // ------------------------------------------------------------------

    private Turn toConfirm(DialogueSession session, String prefix, Turn.Slot picked) {
        session.setPendingAction("BOOK");
        session.setPendingDoctorId(picked.doctorId());
        session.setPendingDate(picked.date().toString());
        session.setPendingTime(picked.time());
        session.setPendingCabinet(picked.cabinet());
        session.setPendingSummary(picked.spoken());
        session.setOfferedSlots(encode(List.of(picked)));
        session.setStage("PENDING_CONFIRMATION");
        String reply = prefix.isEmpty()
                ? picked.spoken() + ". Записываю? Ответьте «да» или «нет»."
                : prefix + picked.spoken() + ". Верно?";
        session.append("ассистент", reply);
        return Turn.continueWith(reply, "PENDING_CONFIRMATION", List.of(picked), 0);
    }

    private Turn onConfirm(DialogueSession session, String text) {
        PolicyRules.Answer answer = PolicyRules.answer(text);

        // пациент ответил не «да/нет», а назвал другое время или специальность
        if (answer == PolicyRules.Answer.UNKNOWN) {
            List<Turn.Slot> offered = decode(session.getOfferedSlots());
            Turn.Slot another = pickSlot(offered, text);
            if (another != null) {
                session.clearPending();
                return toConfirm(session, "Понял. ", another);
            }
            if ("BOOK".equals(session.getPendingAction()) && session.getSpecialty() != null) {
                LocalDate today = LocalDate.now();
                TimeResolver.TimePreference pref = TimeResolver.resolve(List.of(), text, today);
                Optional<Turn.Slot> other = slots
                        .findSlot(session.getSpecialty(), pref, today)
                        .map(this::toSlot);
                if (other.isPresent()) {
                    session.clearPending();
                    return toConfirm(session, "Понял. ", other.get());
                }
            }
            String reply = "Подтвердите, пожалуйста: " + session.getPendingSummary()
                    + ". Ответьте «да» или «нет».";
            session.append("ассистент", reply);
            return Turn.continueWith(reply, "PENDING_CONFIRMATION", List.of(), 0);
        }

        if (answer == PolicyRules.Answer.NO) {
            session.clearPending();
            session.setStage("OFFER");
            String reply = "Хорошо, не записываю. Назовите другое время или другого врача.";
            session.append("ассистент", reply);
            return Turn.continueWith(reply, "OFFER", List.of(), 0);
        }

        if ("RESCHEDULE".equals(session.getPendingAction())) {
            return doReschedule(session);
        }
        return doBook(session);
    }

    // ------------------------------------------------------------------
    // Шаг: создание записи
    // ------------------------------------------------------------------

    private Turn doBook(DialogueSession session) {
        String login = session.getPatientLogin();
        if (login == null || login.isBlank()) {
            return transfer(session, "Не получается определить пациента. "
                    + "Соединяю с регистратором.", "R-07");
        }
        try {
            Map<String, Object> a = appointments.book(login, session.getPendingDoctorId(),
                    LocalDate.parse(session.getPendingDate()), session.getPendingTime());
            Integer cabinet = session.getPendingCabinet();
            String reply = "Готово, вы записаны. " + spokenDate(a.get("date"), a.get("time"))
                    + ", " + a.get("doctorName")
                    + (cabinet == null ? "" : ", кабинет " + RussianTime.number(cabinet))
                    + ". SMS придёт на этот номер.";
            session.clearPending();
            session.setStage("CONFIRMED");
            session.append("ассистент", reply);
            return Turn.hangup(reply, "CONFIRMED", "BOOKED", 0);
        } catch (RuntimeException e) {
            log.warn("Запись не создана: {}", e.getMessage());
            session.clearPending();
            session.setStage("OFFER");
            String reply = "Извините, это время только что заняли. Назовите другое время.";
            session.append("ассистент", reply);
            return Turn.continueWith(reply, "OFFER", List.of(), 0);
        }
    }

    // ------------------------------------------------------------------
    // Дубликат -> предложение переноса (переход в С-03)
    // ------------------------------------------------------------------

    /**
     * У пациента уже есть активная запись на эту специальность.
     * Регламент С-01 требует предложить перенос (сценарий С-03), а не записать вторую.
     */
    private Turn offerMove(DialogueSession session, String greeting, Map<String, Object> existing) {
        session.setPendingAppointmentId(((Number) existing.get("id")).longValue());
        session.setStage("DUP");
        String reply = greeting + "У вас уже есть запись: " + existing.get("doctorName")
                + ", " + spokenDate(existing.get("date"), existing.get("time"))
                + ". Хотите перенести её?";
        session.append("ассистент", reply);
        return Turn.continueWith(reply, "DUP", List.of(), 0);
    }

    private Turn onDuplicate(DialogueSession session, String text) {
        PolicyRules.Answer answer = PolicyRules.answer(text);
        if (answer == PolicyRules.Answer.YES) {
            return offerReschedule(session);
        }
        if (answer == PolicyRules.Answer.UNKNOWN && mentionsWhen(text)) {
            // Пациент ответил не «да», а сразу назвал время: «вторник», «завтра»,
            // «на 20 октября». Понимаем это как «перенесите на это время».
            rememberTime(session, parser.parse(text), text);
            return offerReschedule(session);
        }
        if (answer == PolicyRules.Answer.NO) {
            session.setDupDecision("keep");
            session.setStage("OFFER");
            String reply = "Хорошо, старую запись оставляю. Назовите удобное время для новой.";
            session.append("ассистент", reply);
            return Turn.continueWith(reply, "OFFER", List.of(), 0);
        }
        String reply = "У вас уже есть запись. Перенести её — или записаться ещё раз?";
        session.append("ассистент", reply);
        return Turn.continueWith(reply, "DUP", List.of(), 0);
    }

    /** Названо ли в реплике время или дата. */
    private boolean mentionsWhen(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String q = ClinicalKnowledgeBase.normalize(text);
        if (q.contains("завтра") || q.contains("послезавтра") || q.contains("сегодня")) {
            return true;
        }
        return RussianTime.parseDayMonth(q, LocalDate.now()) != null
                || RussianTime.parseNumericDate(q, LocalDate.now()) != null
                || RussianTime.partOfDay(q) != null
                || byWeekday(q, LocalDate.now()) != null;
    }

    /** С-03: предложить новые слоты для переноса существующей записи. */
    private Turn offerReschedule(DialogueSession session) {
        String specialty = session.getSpecialty();
        LocalDate today = LocalDate.now();
        TimeResolver.TimePreference pref = from(session);

        // Если пациент сразу назвал конкретное время («на 20 октября»), переспрашивать
        // «когда вам удобно» незачем — слот уже известен, идём к подтверждению.
        boolean named = session.getWantedDate() != null || session.getWantedTime() != null;
        if (named && !pref.isEmpty()) {
            Optional<Turn.Slot> exact = slots.findSlot(specialty, pref, today).map(this::toSlot);
            if (exact.isPresent()) {
                return toRescheduleConfirm(session, exact.get());
            }
        }

        List<SlotResolver.Slot> found = slots.offerSlots(specialty, pref, today, MAX_OPTIONS);
        if (found.isEmpty()) {
            String reply = "Свободного времени для переноса сейчас нет. "
                    + "Могу оставить запись как есть.";
            session.append("ассистент", reply);
            return Turn.continueWith(reply, "RESCHEDULE", List.of(), 0);
        }
        List<Turn.Slot> options = found.stream().map(this::toSlot).toList();
        StringBuilder sb = new StringBuilder("Перенести на: ");
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(options.get(i).spokenCompact(today));
        }
        sb.append(". Когда вам удобно?");
        String reply = sb.toString();
        session.setOfferedSlots(encode(options));
        session.setPendingAction("RESCHEDULE");
        session.setStage("RESCHEDULE");
        session.append("ассистент", reply);
        return Turn.continueWith(reply, "RESCHEDULE", options, 0);
    }

    /** Подтверждение переноса: действие RESCHEDULE и та же фраза подтверждения. */
    private Turn toRescheduleConfirm(DialogueSession session, Turn.Slot picked) {
        session.setPendingAction("RESCHEDULE");
        session.setPendingDoctorId(picked.doctorId());
        session.setPendingDate(picked.date().toString());
        session.setPendingTime(picked.time());
        session.setPendingCabinet(picked.cabinet());
        session.setPendingSummary(picked.spoken());
        session.setOfferedSlots(encode(List.of(picked)));
        session.setStage("PENDING_CONFIRMATION");
        String reply = "Переношу на " + picked.spoken() + ". Верно?";
        session.append("ассистент", reply);
        return Turn.continueWith(reply, "PENDING_CONFIRMATION", List.of(picked), 0);
    }

    private Turn onReschedule(DialogueSession session, PatientIdentity who, String text) {
        if (session.getOfferedSlots() != null && !session.getOfferedSlots().isBlank()) {
            List<Turn.Slot> offered = decode(session.getOfferedSlots());
            Turn.Slot picked = pickSlot(offered, text);
            if (picked != null) {
                return toRescheduleConfirm(session, picked);
            }
        }
        // не выбрал из списка — пробуем разобрать названное время
        if (session.getSpecialty() != null) {
            LocalDate today = LocalDate.now();
            TimeResolver.TimePreference pref = TimeResolver.resolve(List.of(), text, today);
            Optional<Turn.Slot> other = slots.findSlot(session.getSpecialty(), pref, today)
                    .map(this::toSlot);
            if (other.isPresent()) {
                return toRescheduleConfirm(session, other.get());
            }
        }
        String reply = "Назовите дату и время для переноса.";
        session.append("ассистент", reply);
        return Turn.continueWith(reply, "RESCHEDULE", List.of(), 0);
    }

    /**
     * С-03, тело: сначала создать новую запись, и только потом отменить старую.
     * Порядок обязателен — при ошибке старая запись должна остаться в силе.
     */
    private Turn doReschedule(DialogueSession session) {
        String login = session.getPatientLogin();
        Long oldId = session.getPendingAppointmentId();
        if (login == null || login.isBlank() || oldId == null) {
            return transfer(session, "Соединяю с регистратором — он перенесёт запись.", "R-07");
        }
        try {
            Map<String, Object> created = appointments.book(login, session.getPendingDoctorId(),
                    LocalDate.parse(session.getPendingDate()), session.getPendingTime());
            appointments.cancel(login, oldId, false);
            String reply = "Готово, запись перенесена на "
                    + spokenDate(created.get("date"), created.get("time"))
                    + ". Прежняя запись отменена.";
            session.clearPending();
            session.setStage("CONFIRMED");
            session.append("ассистент", reply);
            return Turn.hangup(reply, "CONFIRMED", "RESCHEDULED", 0);
        } catch (RuntimeException e) {
            log.warn("Перенос не выполнен: {}", e.getMessage());
            String reply = "Извините, не получилось перенести. Ваша прежняя запись "
                    + "остаётся в силе. Соединяю с регистратором.";
            session.clearPending();
            session.setHandedOff(true);
            session.setStage("OPERATOR");
            session.append("ассистент", reply);
            return Turn.transfer(reply, "R-06", "ESCALATED", 0);
        }
    }

    // ------------------------------------------------------------------
    // Шаг: идентификация
    // ------------------------------------------------------------------

    private Turn onIdentity(DialogueSession session, PatientIdentity who, String text) {
        PatientNameParser.Data data = PatientNameParser.parse(text);
        String login = session.getPatientLogin() == null || session.getPatientLogin().isBlank()
                ? (who.login()) : session.getPatientLogin();

        // пришли с HTTP — пациент уже аутентифицирован, ищем только ФИО/ДР
        if (login != null && !login.isBlank() && !data.hasFio() && !data.hasBirthDate()) {
            PatientIdentity found = identity.findByFioAndBirthDate(who.fio(), who.birthDate())
                    .orElse(who);
            session.setPatientLogin(found.login());
            return continueScenario(session, found);
        }

        if (!data.hasFio() && !data.hasBirthDate()) {
            String reply = "Назовите, пожалуйста, фамилию, имя, отчество и дату рождения.";
            session.append("ассистент", reply);
            return Turn.continueWith(reply, "IDENTITY", List.of(), 0);
        }
        if (!data.hasSurname()) {
            String reply = "Назовите, пожалуйста, фамилию.";
            session.append("ассистент", reply);
            return Turn.continueWith(reply, "IDENTITY", List.of(), 0);
        }
        if (!data.hasBirthDate()) {
            String reply = "Назовите, пожалуйста, дату рождения.";
            session.append("ассистент", reply);
            return Turn.continueWith(reply, "IDENTITY", List.of(), 0);
        }

        // Названо только одно слово — ищем по фамилии, но лишь если такая
        // фамилия в базе единственная. Иначе перевод: определить, КТО это,
        // по одной фамилии нельзя (регламент 3.5).
        Optional<PatientIdentity> found = data.hasFio()
                ? identity.findByFioAndBirthDate(data.fio(), data.birthDate())
                : identity.findByLastName(data.surname());
        if (found.isEmpty()) {
            // Р-07: не нашли. Повторно просим только недостающее, ничего не подсказывая.
            String reply = "Не получается найти вас в базе. Проверьте, пожалуйста, "
                    + "фамилию и дату рождения.";
            session.append("ассистент", reply);
            return Turn.continueWith(reply, "IDENTITY", List.of(), 1);
        }
        PatientIdentity p = found.get();
        session.setPatientLogin(p.login());
        return continueScenario(session, p);
    }

    /** Идентификация прошла — продолжаем сценарий с того места, где остановились. */
    private Turn continueScenario(DialogueSession session, PatientIdentity p) {
        LocalDate today = LocalDate.now();
        TimeResolver.TimePreference pref = from(session);
        String greeting = p.addressName().isBlank() ? "" : "Спасибо, " + p.addressName() + ". ";

        // Повторная запись на ту же специальность — тоже повод предложить перенос,
        // иначе пациент получит вторую запись к тому же врачу мимо сценария С-03.
        Optional<Map<String, Object>> dupAgain = activeBooking(p, session.getSpecialty());
        if (dupAgain.isPresent()) {
            return offerMove(session, greeting, dupAgain.get());
        }

        // К конкретному слоту идём, только если пациент САМ назвал дату или время.
        // Если пожелания не было, предложить выбор — правильнее, чем записать «на первый
        // свободный» без спроса: дата записи обязана быть подтверждена пациентом.
        boolean hasWish = session.getWantedDate() != null || session.getWantedTime() != null;
        if (hasWish && !pref.isEmpty()) {
            Optional<Turn.Slot> chosen = slots.findSlot(session.getSpecialty(), pref, today)
                    .map(this::toSlot);
            if (chosen.isPresent()) {
                return toConfirm(session, greeting, chosen.get());
            }
        }
        return offerSlots(session, greeting);
    }

    // ------------------------------------------------------------------
    // Общее
    // ------------------------------------------------------------------

    /** Активная запись пациента по этой специальности. */
    Optional<Map<String, Object>> activeBooking(PatientIdentity who, String specialty) {
        if (who == null || !who.identified()) {
            return Optional.empty();
        }
        for (Map<String, Object> a : appointments.listFor(who.login(), false)) {
            if ("CONFIRMED".equals(a.get("status"))
                    && (specialty == null || specialty.equals(a.get("specialty")))) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    /** Последняя подтверждённая запись пациента — для ответа «вы записаны». */
    Map<String, Object> lastBooking(String login) {
        if (login == null || login.isBlank()) {
            return null;
        }
        List<Map<String, Object>> all = appointments.listFor(login, false);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private Turn transfer(DialogueSession session, String reply, String rule) {
        session.setHandedOff(true);
        session.setStage("OPERATOR");
        session.append("ассистент", reply);
        return Turn.transfer(reply, rule, "ESCALATED", session.getMissCount());
    }

    Turn.Slot toSlot(SlotResolver.Slot s) {
        return new Turn.Slot(s.doctorId(), s.fio(), s.specialty(), s.date(), s.time(), s.cabinet());
    }

    /** Дата и время словами — так требует регламент (§3.2). */
    static String spokenDate(Object date, Object time) {
        if (date == null) {
            return "";
        }
        LocalDate d = LocalDate.parse(String.valueOf(date));
        String t = time == null ? null : String.valueOf(time);
        return t == null || t.isBlank()
                ? RussianTime.sayDate(d)
                : RussianTime.sayDateTime(d, t);
    }

    // --- предложенные слоты хранятся в сессии строкой ---

    static String encode(List<Turn.Slot> slots) {
        StringBuilder sb = new StringBuilder();
        for (Turn.Slot s : slots) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(s.doctorId()).append('|').append(s.date()).append('|')
                    .append(s.time()).append('|').append(s.cabinet() == null ? "" : s.cabinet())
                    .append('|').append(s.fio()).append('|').append(s.specialty() == null ? "" : s.specialty());
        }
        return sb.toString();
    }

    static List<Turn.Slot> decode(String packed) {
        List<Turn.Slot> result = new ArrayList<>();
        if (packed == null || packed.isBlank()) {
            return result;
        }
        for (String part : packed.split(";")) {
            String[] f = part.split("\\|", -1);
            if (f.length < 5) {
                continue;
            }
            result.add(new Turn.Slot(Long.valueOf(f[0]), f[4], f.length > 5 ? f[5] : null,
                    LocalDate.parse(f[1]), f[2],
                    f[3].isBlank() ? null : Integer.valueOf(f[3])));
        }
        return result;
    }

    /** Восстанавливает пожелание пациента, сохранённое в сессии. */
    static TimeResolver.TimePreference from(DialogueSession session) {
        LocalDate date = session.getWantedDate() == null || session.getWantedDate().isBlank()
                ? null : LocalDate.parse(session.getWantedDate());
        String time = session.getWantedTime() == null || session.getWantedTime().isBlank()
                ? null : session.getWantedTime();
        return new TimeResolver.TimePreference(date, time, null, null, null, false,
                date == null && time == null ? "" : "по выбору пациента");
    }
}