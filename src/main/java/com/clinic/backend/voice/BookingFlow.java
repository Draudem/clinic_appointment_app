package com.clinic.backend.voice;

import com.clinic.backend.appointment.AppointmentService;
import com.clinic.backend.identity.PatientIdentity;
import com.clinic.backend.identity.PatientIdentityResolver;
import com.clinic.backend.identity.PatientNameParser;
import com.clinic.backend.mis.*;
import com.clinic.backend.mis.dto.MisClientDto;
import com.clinic.backend.mis.dto.MisVisit;
import com.clinic.backend.rag.ClinicalKnowledgeBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Сценарий С-01 «Запись на приём» по регламенту РГ-ТЕЛ-01.
 */
@Service
public class BookingFlow {

    private static final Logger log = LoggerFactory.getLogger(BookingFlow.class);

    private static final int RESCHEDULE_OPTIONS = 3;
    private static final int BOOKING_OPTIONS = 1;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATETIME_FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final CommandParser parser;
    private final SlotResolver slots;
    private final AppointmentService appointments;
    private final PatientIdentityResolver identity;
    private final DialogueSessionRepository sessions;
    private final ScheduleQueryService scheduleQueries;
    private final MisClient misClient;
    private final EscalationService escalationService;

    public BookingFlow(CommandParser parser, SlotResolver slots, AppointmentService appointments,
                       PatientIdentityResolver identity, DialogueSessionRepository sessions,
                       ScheduleQueryService scheduleQueries, MisClient misClient,
                       EscalationService escalationService) {
        this.parser = parser;
        this.slots = slots;
        this.appointments = appointments;
        this.identity = identity;
        this.sessions = sessions;
        this.scheduleQueries = scheduleQueries;
        this.misClient = misClient;
        this.escalationService = escalationService;
    }

    @Transactional
    public Turn handle(DialogueSession session, PatientIdentity who, String text) {
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

    private Turn onScheduleInfo(DialogueSession session, PatientIdentity who,
                                ScheduleInfoRule.ScheduleInfo info) {
        Optional<ScheduleQueryService.Reply> answer = scheduleQueries.answer(info);
        if (answer.isEmpty()) return onIntent(session, who, info.text());
        ScheduleQueryService.Reply reply = answer.get();

        if (reply.transfer()) return transfer(session, reply.text(), reply.rule());

        if (reply.specialty() != null && !reply.specialty().isBlank()) {
            session.setSpecialty(reply.specialty());
        }
        session.setStage(reply.text().endsWith("Записать вас?") ? "SCHEDULE_OFFER" : "SPECIALTY");
        session.append("ассистент", reply.text());
        return Turn.continueWith(reply.text(), session.getStage(), List.of(), 0);
    }

    private Turn onScheduleOffer(DialogueSession session, PatientIdentity who, String text) {
        PolicyRules.Answer answer = PolicyRules.answer(text);
        session.setStage("SPECIALTY");
        if (answer == PolicyRules.Answer.YES) {
            String specialty = session.getSpecialty();
            if (specialty == null || specialty.isBlank()) return onIntent(session, who, text);
            return afterSpecialty(session, who, null);
        }
        if (answer == PolicyRules.Answer.NO) {
            String bye = ScheduleQueryService.BYE;
            session.setStage("DONE");
            session.append("ассистент", bye);
            return Turn.continueWith(bye, "DONE", List.of(), 0);
        }
        return onIntent(session, who, text);
    }

    private Turn onIntent(DialogueSession session, PatientIdentity who, String text) {
        Optional<ScheduleInfoRule.ScheduleInfo> schedule = ScheduleInfoRule.detect(text);
        if (schedule.isPresent()) return onScheduleInfo(session, who, schedule.get());

        ParsedCommand parsed = parser.parse(text);
        session.setPendingFrame(parsed.frame());

        String specialty = parsed.hasSpecialty() ? parsed.specialty() : session.getSpecialty();
        if (specialty == null || specialty.isBlank()) {
            String reply = "К какому врачу хотите записаться? Назовите специальность или скажите, что беспокоит.";
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

        rememberTime(session, parsed, text);
        Optional<Turn.Slot> exact = findExact(session, specialty, parsed, text);
        return afterSpecialty(session, who, exact.orElse(null));
    }

    private void rememberTime(DialogueSession session, ParsedCommand parsed, String text) {
        LocalDate today = LocalDate.now(ZoneOffset.ofHours(4));
        TimeResolver.TimePreference pref = TimeResolver.resolve(parsed.timeFactors(), text, today);
        session.setWantedDate(pref.date() == null ? null : pref.date().toString());
        session.setWantedTime(pref.exact());
    }

    private Optional<Turn.Slot> findExact(DialogueSession session, String specialty,
                                          ParsedCommand parsed, String text) {
        LocalDate today = LocalDate.now(ZoneOffset.ofHours(4));
        TimeResolver.TimePreference pref = TimeResolver.resolve(parsed.timeFactors(), text, today);
        if (pref.isEmpty()) return Optional.empty();
        return slots.findSlot(specialty, pref, today).map(this::toSlot);
    }

    private Turn afterSpecialty(DialogueSession session, PatientIdentity who, Turn.Slot chosen) {
        if (!who.identified()) {
            String reply = "Назовите, пожалуйста, фамилию, имя, отчество и дату рождения.";
            session.setStage("IDENTITY");
            session.append("ассистент", reply);
            return Turn.continueWith(reply, "IDENTITY", List.of(), 0);
        }
        session.setPatientFio(who.fio());
        session.setPatientBirthDate(who.birthDate() == null ? null : who.birthDate().toString());

        String greeting = who.addressName().isBlank() ? "" : "Спасибо, " + who.addressName() + ". ";

        Optional<Map<String, Object>> dup = activeBooking(who, session.getSpecialty());
        if (dup.isPresent()) return offerMove(session, greeting, dup.get());

        if (chosen != null) return toConfirm(session, greeting + "Записываю: ", chosen);

        return offerSlots(session, greeting);
    }

    private Turn offerSlots(DialogueSession session, String greeting) {
        LocalDate today = LocalDate.now(ZoneOffset.ofHours(4));
        TimeResolver.TimePreference pref = from(session);
        List<SlotResolver.Slot> found = slots.offerSlots(session.getSpecialty(), pref, today, BOOKING_OPTIONS);
        if (found.isEmpty()) {
            String reply = greeting + "Свободного времени в ближайшие две недели нет. Могу поставить вас в лист ожидания и перезвонить.";
            session.setStage("OFFER");
            session.setOfferedSlots("");
            session.append("ассистент", reply);
            return Turn.continueWith(reply, "OFFER", List.of(), 0);
        }
        return offerBooking(session, greeting, toSlot(found.get(0)));
    }

    private Turn onOfferChoice(DialogueSession session, String text) {
        List<Turn.Slot> offered = decode(session.getOfferedSlots());
        if (offered.isEmpty()) return offerSlots(session, "");
        Turn.Slot picked = pickSlot(offered, text);
        if (picked == null) {
            String reply = "Скажите, пожалуйста, когда вам удобно: можно назвать дату или выбрать один из предложенных вариантов.";
            session.append("ассистент", reply);
            return Turn.continueWith(reply, "OFFER", offered, 0);
        }
        return toConfirm(session, "", picked);
    }

    Turn.Slot pickSlot(List<Turn.Slot> offered, String text) {
        if (offered.isEmpty() || text == null) return null;
        String q = ClinicalKnowledgeBase.normalize(text);
        LocalDate today = LocalDate.now(ZoneOffset.ofHours(4));
        LocalDate saidDate = RussianTime.parseDayMonth(q, today);
        if (saidDate == null) saidDate = RussianTime.parseNumericDate(q, today);
        if (saidDate == null && q.contains("завтра")) saidDate = today.plusDays(1);
        if (saidDate == null && q.contains("послезавтра")) saidDate = today.plusDays(2);
        if (saidDate == null) saidDate = byWeekday(q, today);

        String saidTime = null;
        java.time.LocalTime digits = RussianTime.parseHhmm(q);
        if (digits != null) {
            saidTime = HHMM.format(digits);
        } else if (looksLikeTime(q)) {
            java.time.LocalTime words = RussianTime.parseWordsTime(q);
            if (words != null) saidTime = HHMM.format(words);
        }

        if (saidDate != null || saidTime != null) {
            for (Turn.Slot s : offered) {
                boolean dateOk = saidDate == null || saidDate.equals(s.date());
                boolean timeOk = saidTime == null || saidTime.equals(s.time());
                if (dateOk && timeOk) return s;
            }
        }
        boolean namedSomething = saidDate != null || saidTime != null
                || containsWeekdayWord(q, today) || RussianTime.partOfDay(q) != null;
        if (!namedSomething) {
            Integer idx = PolicyRules.ordinalChoice(text);
            if (idx != null && idx >= 0 && idx < offered.size()) return offered.get(idx);
        }
        if (offered.size() == 1 && PolicyRules.answer(text) == PolicyRules.Answer.YES) return offered.get(0);
        return null;
    }

    private static boolean looksLikeTime(String q) {
        for (String mark : new String[]{"час", "утра", "вечера", "дня", "ночи", "дня", "во сколько"}) {
            if (q.contains(mark)) return true;
        }
        return false;
    }

    private static final java.time.format.DateTimeFormatter HHMM =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm");

    private LocalDate byWeekday(String q, LocalDate today) {
        for (java.time.DayOfWeek d : java.time.DayOfWeek.values()) {
            String stem = switch (d) {
                case MONDAY -> "понедельник"; case TUESDAY -> "вторник";
                case WEDNESDAY -> "сред"; case THURSDAY -> "четверг";
                case FRIDAY -> "пятниц"; case SATURDAY -> "суббот";
                case SUNDAY -> "воскресень";
            };
            if (q.contains(stem)) return RussianTime.nextWeekday(today, d);
        }
        return null;
    }

    private boolean containsWeekdayWord(String q, LocalDate today) {
        return byWeekday(q, today) != null;
    }

    private Turn toConfirm(DialogueSession session, String prefix, Turn.Slot picked) {
        session.setPendingAction("BOOK");
        session.setPendingDoctorId(picked.doctorId());
        session.setPendingDate(picked.date().toString());
        preparePending(session, picked);
        String reply = prefix.isEmpty() ? picked.spoken() + ". Записать вас?" : prefix + picked.spoken() + ". Верно?";
        session.append("ассистент", reply);
        return Turn.continueWith(reply, "PENDING_CONFIRMATION", List.of(picked), 0);
    }

    private Turn offerBooking(DialogueSession session, String greeting, Turn.Slot picked) {
        preparePending(session, picked);
        String reply = greeting + picked.spoken() + ". Записать вас?";
        session.append("ассистент", reply);
        return Turn.continueWith(reply, "PENDING_CONFIRMATION", List.of(picked), 0);
    }

    private void preparePending(DialogueSession session, Turn.Slot picked) {
        session.setPendingAction("BOOK");
        session.setPendingDoctorId(picked.doctorId());
        session.setPendingDate(picked.date().toString());
        session.setPendingTime(picked.time());
        session.setPendingCabinet(picked.cabinet());
        session.setPendingSummary(picked.spoken());
        session.setOfferedSlots(encode(List.of(picked)));
        session.setStage("PENDING_CONFIRMATION");
    }

    private Turn onConfirm(DialogueSession session, String text) {
        PolicyRules.Answer answer = PolicyRules.answer(text);
        if (answer == PolicyRules.Answer.UNKNOWN) {
            List<Turn.Slot> offered = decode(session.getOfferedSlots());
            Turn.Slot another = pickSlot(offered, text);
            if (another != null) {
                session.clearPending();
                return toConfirm(session, "Поняла. ", another);
            }
            if ("BOOK".equals(session.getPendingAction()) && session.getSpecialty() != null) {
                LocalDate today = LocalDate.now(ZoneOffset.ofHours(4));
                TimeResolver.TimePreference pref = TimeResolver.resolve(List.of(), text, today);
                Optional<Turn.Slot> other = slots.findSlot(session.getSpecialty(), pref, today).map(this::toSlot);
                if (other.isPresent()) {
                    session.clearPending();
                    return toConfirm(session, "Поняла. ", other.get());
                }
            }
            String reply = "Подтвердите, пожалуйста: " + session.getPendingSummary() + ". Ответьте «да» или «нет».";
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

        if ("RESCHEDULE".equals(session.getPendingAction())) return doReschedule(session);
        return doBook(session);
    }

    private Turn doBook(DialogueSession session) {
        String login = session.getPatientLogin();
        if (login == null || login.isBlank()) {
            return transfer(session, "Не получается определить пациента. Соединяю с регистратором.", "R-07");
        }

        PatientIdentity who = identity.findByLogin(login).orElse(null);
        if (who == null || who.last() == null || who.birthDate() == null) {
            return transfer(session, "В карточке не хватает данных. Соединяю с регистратором.", "R-07");
        }

        try {
            MisClientDto misPatient = misClient.findOrCreateClient(who.last(), who.first(), who.birthDate());

            LocalDate d = LocalDate.parse(session.getPendingDate());
            String dateStr = d.format(DATE_FMT);
            String scheduledAt = dateStr + " " + session.getPendingTime();

            MisVisit visit = misClient.createVisit(misPatient.id(), session.getPendingDoctorId(), scheduledAt);

            appointments.book(login, session.getPendingDoctorId(), d, session.getPendingTime());

            Integer cabinet = session.getPendingCabinet();
            // scheduledAt приходит из МИС в формате ДД.ММ.ГГГГ ЧЧ:ММ — парсим общим парсером,
            // а не LocalDate.parse (тот ждёт ISO и падал бы на живом стенде)
            String reply = "Готово, вы записаны. " + spokenDate(visit.scheduledAt(), "")
                    + ", " + visit.doctor().fullName()
                    + (cabinet == null ? "" : ", кабинет " + RussianTime.number(cabinet))
                    + ". SMS придёт на этот номер.";

            session.clearPending();
            session.setStage("CONFIRMED");
            session.append("ассистент", reply);
            return Turn.hangup(reply, "CONFIRMED", "BOOKED", 0);

        } catch (MisConflictException | MisBadRequestException e) {
            log.warn("МИС отклонила запись: {}", e.getMessage());
            session.clearPending();
            session.setStage("OFFER");
            String reply = "Извините, это время только что заняли. Назовите другое время.";
            session.append("ассистент", reply);
            return Turn.continueWith(reply, "OFFER", List.of(), 0);

        } catch (MisRateLimitedException e) {
            log.warn("МИС: 429 при записи/переносе (Retry-After={} с): {}",
                    e.retryAfterSeconds(), e.getMessage());
            session.clearPending();
            // Не «система недоступна», а «секунду подождите»: запись не потеряна,
            // просто МИС просит сбавить темп. Пациенту честнее сказать именно так.
            return transfer(session,
                    "Секунду, проверяю расписание ещё раз. Соединяю с регистратором — он запишет без задержки.",
                    "R-06");


        } catch (MisUnavailableException e) {
            log.error("МИС недоступна при создании записи: {}", e.getMessage());
            session.clearPending();
            return transfer(session, "Система записи временно недоступна. Соединяю с регистратором.", "R-06");
        }
    }

    private Turn offerMove(DialogueSession session, String greeting, Map<String, Object> existing) {
        session.setPendingAppointmentId(((Number) existing.get("id")).longValue());
        session.setStage("DUP");
        String reply = greeting + "У вас уже есть запись: " + existing.get("doctorName") + ", "
                + spokenDate(existing.get("date"), existing.get("time")) + ". Хотите перенести её?";
        session.append("ассистент", reply);
        return Turn.continueWith(reply, "DUP", List.of(), 0);
    }

    private Turn onDuplicate(DialogueSession session, String text) {
        PolicyRules.Answer answer = PolicyRules.answer(text);
        if (answer == PolicyRules.Answer.YES) return offerReschedule(session);
        if (answer == PolicyRules.Answer.UNKNOWN && mentionsWhen(text)) {
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

    private boolean mentionsWhen(String text) {
        if (text == null || text.isBlank()) return false;
        String q = ClinicalKnowledgeBase.normalize(text);
        if (q.contains("завтра") || q.contains("послезавтра") || q.contains("сегодня")) return true;
        return RussianTime.parseDayMonth(q, LocalDate.now()) != null
                || RussianTime.parseNumericDate(q, LocalDate.now()) != null
                || RussianTime.partOfDay(q) != null
                || byWeekday(q, LocalDate.now()) != null;
    }

    private Turn offerReschedule(DialogueSession session) {
        String specialty = session.getSpecialty();
        LocalDate today = LocalDate.now(ZoneOffset.ofHours(4));
        TimeResolver.TimePreference pref = from(session);

        boolean named = session.getWantedDate() != null || session.getWantedTime() != null;
        if (named && !pref.isEmpty()) {
            Optional<Turn.Slot> exact = slots.findSlot(specialty, pref, today).map(this::toSlot);
            if (exact.isPresent()) return toRescheduleConfirm(session, exact.get());
        }

        List<SlotResolver.Slot> found = slots.offerSlots(specialty, pref, today, RESCHEDULE_OPTIONS);
        if (found.isEmpty()) {
            String reply = "Свободного времени для переноса сейчас нет. Могу оставить запись как есть.";
            session.append("ассистент", reply);
            return Turn.continueWith(reply, "RESCHEDULE", List.of(), 0);
        }
        List<Turn.Slot> options = found.stream().map(this::toSlot).toList();
        StringBuilder sb = new StringBuilder("Перенести на: ");
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) sb.append("; ");
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
            if (picked != null) return toRescheduleConfirm(session, picked);
        }
        if (session.getSpecialty() != null) {
            LocalDate today = LocalDate.now(ZoneOffset.ofHours(4));
            TimeResolver.TimePreference pref = TimeResolver.resolve(List.of(), text, today);
            Optional<Turn.Slot> other = slots.findSlot(session.getSpecialty(), pref, today).map(this::toSlot);
            if (other.isPresent()) return toRescheduleConfirm(session, other.get());
        }
        String reply = "Назовите дату и время для переноса.";
        session.append("ассистент", reply);
        return Turn.continueWith(reply, "RESCHEDULE", List.of(), 0);
    }

    private Turn doReschedule(DialogueSession session) {
        String login = session.getPatientLogin();
        Long oldId = session.getPendingAppointmentId();
        if (login == null || login.isBlank() || oldId == null) {
            return transfer(session, "Соединяю с регистратором — он перенесёт запись.", "R-07");
        }

        PatientIdentity who = identity.findByLogin(login).orElse(null);
        if (who == null || who.last() == null || who.birthDate() == null) {
            return transfer(session, "В карточке не хватает данных. Соединяю с регистратором.", "R-07");
        }

        try {
            MisClientDto misPatient = misClient.findOrCreateClient(who.last(), who.first(), who.birthDate());

            LocalDate d = LocalDate.parse(session.getPendingDate());
            String dateStr = d.format(DATE_FMT);
            String scheduledAt = dateStr + " " + session.getPendingTime();

            MisVisit visit = misClient.createVisit(misPatient.id(), session.getPendingDoctorId(), scheduledAt);

            try {
                misClient.cancelVisit(oldId);
            } catch (Exception e) {
                log.warn("Не удалось отменить старую запись {} в МИС: {}", oldId, e.getMessage());
            }

            appointments.book(login, session.getPendingDoctorId(), d, session.getPendingTime());
            appointments.cancel(login, oldId, false);

            String reply = "Готово, запись перенесена на " + spokenDate(visit.scheduledAt(), "")
                    + ". Прежняя запись отменена.";
            session.clearPending();
            session.setStage("CONFIRMED");
            session.append("ассистент", reply);
            return Turn.hangup(reply, "CONFIRMED", "RESCHEDULED", 0);

        } catch (MisConflictException | MisBadRequestException e) {
            log.warn("МИС отклонила перенос: {}", e.getMessage());
            String reply = "Извините, это время только что заняли. Назовите другое время.";
            session.clearPending();
            session.setStage("OFFER");
            session.append("ассистент", reply);
            return Turn.continueWith(reply, "OFFER", List.of(), 0);

        } catch (MisRateLimitedException e) {
            log.warn("МИС: 429 при записи/переносе (Retry-After={} с): {}",
                    e.retryAfterSeconds(), e.getMessage());
            session.clearPending();
            // Не «система недоступна», а «секунду подождите»: запись не потеряна,
            // просто МИС просит сбавить темп. Пациенту честнее сказать именно так.
            return transfer(session,
                    "Секунду, проверяю расписание ещё раз. Соединяю с регистратором — он запишет без задержки.",
                    "R-06");


        } catch (MisUnavailableException e) {
            log.error("МИС недоступна при переносе: {}", e.getMessage());
            String reply = "Извините, не получилось перенести. Ваша прежняя запись остаётся в силе. Соединяю с регистратором.";
            session.clearPending();
            session.setHandedOff(true);
            session.setStage("OPERATOR");
            session.append("ассистент", reply);
            return Turn.transfer(reply, "R-06", "ESCALATED", 0);
        }
    }

    private Turn onIdentity(DialogueSession session, PatientIdentity who, String text) {
        PatientNameParser.Data data = PatientNameParser.parse(text);
        String login = session.getPatientLogin() == null || session.getPatientLogin().isBlank()
                ? (who.login()) : session.getPatientLogin();

        if (login != null && !login.isBlank() && !data.hasFio() && !data.hasBirthDate()) {
            PatientIdentity found = identity.findByFioAndBirthDate(who.fio(), who.birthDate()).orElse(who);
            session.setPatientLogin(found.login());
            return continueScenario(session, found);
        }

        String fio = data.hasFio() ? data.fio() : session.getSaidFio();
        LocalDate birth = data.hasBirthDate() ? data.birthDate() : rememberedBirth(session);
        if (fio != null) session.setSaidFio(fio);
        if (birth != null) session.setSaidBirthDate(birth.toString());

        if (fio == null && birth == null) {
            return askIdentity(session, "Назовите, пожалуйста, фамилию, имя, отчество и дату рождения.");
        }
        if (fio == null) return askIdentity(session, "Назовите, пожалуйста, фамилию, имя и отчество.");
        if (birth == null) return askIdentity(session, "Назовите, пожалуйста, дату рождения.");

        Optional<PatientIdentity> found = identity.findByFioAndBirthDate(fio, birth);
        if (found.isEmpty()) return onIdentityNotFound(session);

        session.setPatientLogin(found.get().login());
        return continueScenario(session, found.get());
    }

    private static LocalDate rememberedBirth(DialogueSession session) {
        String v = session.getSaidBirthDate();
        if (v == null || v.isBlank()) return null;
        try { return LocalDate.parse(v); } catch (RuntimeException e) { return null; }
    }

    private Turn askIdentity(DialogueSession session, String reply) {
        session.append("ассистент", reply);
        return Turn.continueWith(reply, "IDENTITY", List.of(), 0);
    }

    private Turn onIdentityNotFound(DialogueSession session) {
        int misses = session.getIdentityMisses() + 1;
        session.setIdentityMisses(misses);
        if (misses >= 2) {
            String reply = "Проверьте, пожалуйста, данные в регистратуре. Соединяю с сотрудником.";
            return transfer(session, reply, "Р-07");
        }
        String reply = "Не получается найти вас в базе. Проверьте, пожалуйста, фамилию и дату рождения.";
        session.append("ассистент", reply);
        return Turn.continueWith(reply, "IDENTITY", List.of(), misses);
    }

    private Turn continueScenario(DialogueSession session, PatientIdentity p) {
        LocalDate today = LocalDate.now(ZoneOffset.ofHours(4));
        TimeResolver.TimePreference pref = from(session);
        String greeting = p.addressName().isBlank() ? "" : "Спасибо, " + p.addressName() + ". ";

        Optional<Map<String, Object>> dupAgain = activeBooking(p, session.getSpecialty());
        if (dupAgain.isPresent()) return offerMove(session, greeting, dupAgain.get());

        boolean hasWish = session.getWantedDate() != null || session.getWantedTime() != null;
        if (hasWish && !pref.isEmpty()) {
            Optional<Turn.Slot> chosen = slots.findSlot(session.getSpecialty(), pref, today).map(this::toSlot);
            if (chosen.isPresent()) return toConfirm(session, greeting, chosen.get());
        }
        return offerSlots(session, greeting);
    }

    Optional<Map<String, Object>> activeBooking(PatientIdentity who, String specialty) {
        if (who == null || !who.identified()) return Optional.empty();
        for (Map<String, Object> a : appointments.listFor(who.login(), false)) {
            if ("CONFIRMED".equals(a.get("status"))
                    && (specialty == null || specialty.equals(a.get("specialty")))) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    Map<String, Object> lastBooking(String login) {
        if (login == null || login.isBlank()) return null;
        List<Map<String, Object>> all = appointments.listFor(login, false);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private Turn transfer(DialogueSession session, String reply, String rule) {
        session.setHandedOff(true);
        session.setStage("OPERATOR");
        session.append("ассистент", reply);

        if (session.getPatientLogin() != null && !session.getPatientLogin().isBlank()) {
            identity.findByLogin(session.getPatientLogin()).ifPresent(who -> {
                if (who.last() != null && who.birthDate() != null) {
                    escalationService.escalate(who.last(), who.first(), who.birthDate(), reply, rule);
                }
            });
        }

        return Turn.transfer(reply, rule, "ESCALATED", session.getMissCount());
    }

    Turn.Slot toSlot(SlotResolver.Slot s) {
        return new Turn.Slot(s.doctorId(), s.fio(), s.specialty(), s.date(), s.time(), s.cabinet());
    }

    /**
     * Разбирает дату/время из МИС и из локальной БД в один формат.
     *
     * <p>МИС возвращает {@code scheduledAt} строкой {@code ДД.ММ.ГГГГ ЧЧ:ММ} (документация
     * стенда), а из БД приходит ISO-дата {@code YYYY-MM-DD}. Раньше здесь стоял
     * {@code LocalDate.parse(String.valueOf(date))}, и на реальном ответе МИС
     * «16.10.2026 11:00» он падал с DateTimeParseException — то есть запись в МИС
     * создавалась, а ответ пациенту не формировался, и звонок обрывался.
     * Теперь оба формата разбираются явно.
     */
    static String spokenDate(Object date, Object time) {
        if (date == null) return "";
        LocalDate d = parseAnyDate(String.valueOf(date));
        if (d == null) return "";
        String t = time == null || String.valueOf(time).isBlank() ? null : String.valueOf(time);
        return t == null ? RussianTime.sayDate(d) : RussianTime.sayDateTime(d, t);
    }

    /** Разбирает дату в ISO ({@code 2026-10-16}) или в {@code ДД.ММ.ГГГГ [ЧЧ:ММ]}. */
    private static LocalDate parseAnyDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        try {
            return LocalDate.parse(s);                       // ISO
        } catch (DateTimeParseException ignored) {
            // не ISO
        }
        try {
            return LocalDate.parse(s, DATE_FMT);             // ДД.ММ.ГГГГ
        } catch (DateTimeParseException ignored) {
            // не дата-only
        }
        try {
            return LocalDateTime.parse(s, DATETIME_FMT).toLocalDate();   // ДД.ММ.ГГГГ ЧЧ:ММ
        } catch (DateTimeParseException ignored) {
            // не наш формат
        }
        log.warn("Не удалось разобрать дату из ответа МИС: «{}»", raw);
        return null;
    }

    static String encode(List<Turn.Slot> slots) {
        StringBuilder sb = new StringBuilder();
        for (Turn.Slot s : slots) {
            if (sb.length() > 0) sb.append(';');
            sb.append(s.doctorId()).append('|').append(s.date()).append('|')
                    .append(s.time()).append('|').append(s.cabinet() == null ? "" : s.cabinet())
                    .append('|').append(s.fio()).append('|').append(s.specialty() == null ? "" : s.specialty());
        }
        return sb.toString();
    }

    static List<Turn.Slot> decode(String packed) {
        List<Turn.Slot> result = new ArrayList<>();
        if (packed == null || packed.isBlank()) return result;
        for (String part : packed.split(";")) {
            String[] f = part.split("\\|", -1);
            if (f.length < 5) continue;
            result.add(new Turn.Slot(Long.valueOf(f[0]), f[4], f.length > 5 ? f[5] : null,
                    LocalDate.parse(f[1]), f[2],
                    f[3].isBlank() ? null : Integer.valueOf(f[3])));
        }
        return result;
    }

    static TimeResolver.TimePreference from(DialogueSession session) {
        LocalDate date = session.getWantedDate() == null || session.getWantedDate().isBlank()
                ? null : LocalDate.parse(session.getWantedDate());
        String time = session.getWantedTime() == null || session.getWantedTime().isBlank()
                ? null : session.getWantedTime();
        return new TimeResolver.TimePreference(date, time, null, null, null, false,
                date == null && time == null ? "" : "по выбору пациента");
    }
}