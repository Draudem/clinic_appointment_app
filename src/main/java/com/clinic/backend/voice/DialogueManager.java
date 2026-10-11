package com.clinic.backend.voice;

import com.clinic.backend.appointment.AppointmentService;
import com.clinic.backend.doctor.Doctor;
import com.clinic.backend.doctor.DoctorRepository;
import com.clinic.backend.rag.ClinicalKnowledgeBase;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Ведёт диалог записи: разбирает реплику, добирает недостающее, проверяет
 * реальное расписание и предлагает действие. Запись/отмена выполняется ТОЛЬКО
 * после явного подтверждения пациента (этап PENDING_CONFIRMATION). Держит лимит
 * обменов (по умолчанию 5) — после него диалог передаётся оператору (заглушка).
 *
 * Каждый этап (парсер, подбор слота, запись) вынесен в отдельный бин, поэтому
 * любой из них можно заменить, не трогая логику диалога.
 */
@Service
public class DialogueManager {

    private final CommandParser parser;
    private final RuleCommandParser timeHintParser;
    private final SlotResolver slotResolver;
    private final AppointmentService appointments;
    private final DialogueSessionRepository sessions;
    private final DoctorRepository doctors;
    private final VoiceProperties props;

    public DialogueManager(CommandParser parser,
                           RuleCommandParser timeHintParser,
                           SlotResolver slotResolver,
                           AppointmentService appointments,
                           DialogueSessionRepository sessions,
                           DoctorRepository doctors,
                           VoiceProperties props) {
        this.parser = parser;
        this.timeHintParser = timeHintParser;
        this.slotResolver = slotResolver;
        this.appointments = appointments;
        this.sessions = sessions;
        this.doctors = doctors;
        this.props = props;
    }

    @Transactional
    public VoiceReply handle(String login, Long sessionId, String text) {
        DialogueSession session = sessionId == null
                ? new DialogueSession(login)
                : sessions.findById(sessionId).orElseGet(() -> new DialogueSession(login));

        session.append("пациент", text);
        session.incrementExchanges();

        // Лимит обменов -> оператор
        if (session.getExchanges() > props.getMaxExchanges() || session.isHandedOff()) {
            session.setHandedOff(true);
            session.setStage("OPERATOR");
            String reply = "Передаю вас оператору — он продолжит запись. Это диалог №"
                    + session.getId() + ".";
            session.append("ассистент", reply);
            sessions.save(session);
            return build(session, reply, List.of(), null, null, true, false, "(оператор)", "operator");
        }

        ParsedCommand parsed = parser.parse(text);
        // NLU-модель работает с целыми фразами; короткие уточнения («в пятницу»,
        // «в 15:00») она может не разобрать. Тогда добираем время из rule-парсера.
        if (parsed.timeFactors().isEmpty()) {
            ParsedCommand hint = timeHintParser.parse(text);
            if (!hint.timeFactors().isEmpty()) {
                parsed = mergeTime(parsed, hint);
            }
        }

        // --- Этап подтверждения предложенного действия ---
        if ("PENDING_CONFIRMATION".equals(session.getStage()) && session.getPendingAction() != null) {
            Confirm answer = confirm(text);
            if (answer == Confirm.NO) {
                session.clearPending();
                session.setStage("COLLECTING_TIME");
                String reply = "Хорошо, не подтверждаю. Назовите другую дату/время "
                        + "или другую специальность.";
                session.append("ассистент", reply);
                sessions.save(session);
                return build(session, reply, List.of(), null, null, false, false,
                        session.getPendingFrame(), "confirm-no");
            }
            if (answer == Confirm.YES) {
                return executePending(session, login);
            }
            // Ответ не распознан как да/нет. Если пациент назвал новую информацию
            // (специальность/время) — обрабатываем её как новую команду.
            boolean newInfo = parsed.hasSpecialty() || !parsed.timeFactors().isEmpty();
            if (!newInfo) {
                String reply = "Пожалуйста, подтвердите: " + session.getPendingSummary()
                        + ". Ответьте «да» или «нет».";
                session.append("ассистент", reply);
                sessions.save(session);
                return build(session, reply, List.of(), null, proposalOf(session), false, false,
                        session.getPendingFrame(), "confirm-reask");
            }
            session.clearPending();
        }

        String specialty = parsed.hasSpecialty() ? parsed.specialty() : session.getSpecialty();
        if (specialty != null && !specialty.isBlank()) {
            session.setSpecialty(specialty);
        }

        String reply;
        List<String> options = new ArrayList<>();
        Map<String, Object> appointment = null;
        Map<String, Object> proposal = null;
        boolean needsClarification = false;
        boolean unsupported = false;
        String stage = session.getStage();
        LocalDate today = LocalDate.now();

        if ("CANCEL".equals(parsed.intent())) {
            List<Map<String, Object>> mine = appointments.listFor(login, false);
            TimeResolver.TimePreference pref = TimeResolver.resolve(parsed.timeFactors(), today);
            Optional<Map<String, Object>> target = pickCancelTarget(mine, pref, today);
            if (target.isEmpty()) {
                reply = "У вас нет подходящих записей для отмены. "
                        + "Список записей — в разделе «Мои записи».";
                needsClarification = true;
                stage = "COLLECTING_SPECIALTY";
            } else {
                Map<String, Object> a = target.get();
                session.setPendingAction("CANCEL");
                session.setPendingAppointmentId(((Number) a.get("id")).longValue());
                String summary = "отмена записи: " + a.get("doctorName")
                        + " (" + a.get("specialty") + "), " + a.get("date") + " в " + a.get("time");
                session.setPendingSummary(summary);
                session.setPendingFrame(parsed.frame());
                session.setStage("PENDING_CONFIRMATION");
                reply = "Подтвердите, пожалуйста, " + summary + ". Ответьте «да» или «нет».";
                stage = "PENDING_CONFIRMATION";
                proposal = proposalOf(session);
            }
        } else if (parsed.unsupported() && specialty == null) {
            reply = "Я помогаю записаться к врачу. К какому специалисту вас записать? "
                    + "Можно назвать симптом — например, «болит зуб».";
            needsClarification = true;
            unsupported = true;
            stage = "COLLECTING_SPECIALTY";
        } else if (specialty == null) {
            reply = "К какому врачу хотите записаться? Назовите специальность или симптом.";
            needsClarification = true;
            stage = "COLLECTING_SPECIALTY";
        } else if (!slotResolver.specialtyStaffed(specialty)) {
            reply = "Специалиста «" + specialty + "» сейчас нет. Доступные направления: "
                    + String.join(", ", availableSpecialties()) + ".";
            needsClarification = true;
            stage = "COLLECTING_SPECIALTY";
        } else {
            TimeResolver.TimePreference pref = TimeResolver.resolve(parsed.timeFactors(), today);
            Optional<SlotResolver.Slot> slot = slotResolver.findSlot(specialty, pref, today);
            if (slot.isPresent() && !parsed.timeFactors().isEmpty() && !parsed.timeUnknown()) {
                SlotResolver.Slot s = slot.get();
                // НЕ записываем сразу — сначала просим подтверждение.
                session.setPendingAction("BOOK");
                session.setPendingDoctorId(s.doctor().getId());
                session.setPendingDate(s.date().toString());
                session.setPendingTime(s.time());
                session.setPendingSummary(s.doctor().getFio() + " (" + specialty + "), "
                        + s.date() + " в " + s.time());
                session.setPendingFrame(parsed.frame());
                session.setStage("PENDING_CONFIRMATION");
                reply = "Проверьте, пожалуйста: " + session.getPendingSummary()
                        + ". Записываю? Ответьте «да» или «нет».";
                stage = "PENDING_CONFIRMATION";
                proposal = proposalOf(session);
            } else {
                reply = proposeSlots(specialty, today, options, parsed);
                needsClarification = true;
                stage = "COLLECTING_TIME";
            }
        }

        session.setStage(stage);
        session.append("ассистент", reply);
        sessions.save(session);
        return build(session, reply, options, appointment, proposal, false, unsupported,
                parsed.frame(), parsed.engine());
    }

    /** Выполнение подтверждённого действия (BOOK/CANCEL). */
    private VoiceReply executePending(DialogueSession session, String login) {
        String action = session.getPendingAction();
        Map<String, Object> appointment = null;
        String reply;
        if ("CANCEL".equals(action)) {
            Long appointmentId = session.getPendingAppointmentId();
            try {
                appointments.cancel(login, appointmentId, false);
                reply = "Готово: запись отменена.";
                session.setStage("CONFIRMED");
            } catch (RuntimeException e) {
                reply = "Не удалось отменить запись: " + e.getMessage();
                session.setStage("COLLECTING_TIME");
            }
        } else {
            try {
                appointment = appointments.book(login, session.getPendingDoctorId(),
                        LocalDate.parse(session.getPendingDate()), session.getPendingTime());
                reply = "Готово! Записал вас: " + appointment.get("doctorName") + " ("
                        + appointment.get("specialty") + "), " + appointment.get("date")
                        + " в " + appointment.get("time") + ".";
                session.setStage("CONFIRMED");
            } catch (RuntimeException e) {
                reply = "Не получилось записать: " + e.getMessage()
                        + " Давайте выберем другое время.";
                session.setStage("COLLECTING_TIME");
            }
        }
        session.clearPending();
        session.append("ассистент", reply);
        sessions.save(session);
        return build(session, reply, List.of(), appointment, null, false, false,
                "(подтверждено)", "confirm-yes");
    }

    private String proposeSlots(String specialty, LocalDate today, List<String> options, ParsedCommand parsed) {
        List<Doctor> docs = doctors.findBySpecialty(specialty);
        String lead = parsed.timeFactors().isEmpty() || parsed.timeUnknown()
                ? "На какую дату и время записать? "
                : "На это время свободного приёма нет. ";
        for (Doctor d : docs) {
            for (SlotResolver.Slot s : slotResolver.upcomingSlots(d, today, 14)) {
                options.add(s.date() + " " + s.time() + " — " + d.getFio());
                if (options.size() >= 3) {
                    break;
                }
            }
            if (options.size() >= 3) {
                break;
            }
        }
        if (options.isEmpty()) {
            return lead + "Свободных приёмов у «" + specialty + "» в ближайшие 2 недели нет.";
        }
        return lead + "Свободные варианты: " + String.join("; ", options) + ".";
    }

    private Optional<Map<String, Object>> pickCancelTarget(List<Map<String, Object>> mine,
                                                           TimeResolver.TimePreference pref,
                                                           LocalDate today) {
        String wanted = pref.date() == null ? null : pref.date().toString();
        for (Map<String, Object> a : mine) {
            if (!"CONFIRMED".equals(a.get("status"))) {
                continue;
            }
            String date = String.valueOf(a.get("date"));
            if (date.compareTo(today.toString()) < 0) {
                continue;
            }
            if (wanted != null && !wanted.equals(date)) {
                continue;
            }
            return Optional.of(a);
        }
        return Optional.empty();
    }

    private List<String> availableSpecialties() {
        TreeSet<String> names = new TreeSet<>();
        for (Doctor d : doctors.findAll()) {
            names.add(d.getSpecialty());
        }
        return new ArrayList<>(names);
    }

    private Map<String, Object> proposalOf(DialogueSession session) {
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("action", session.getPendingAction());
        proposal.put("summary", session.getPendingSummary());
        proposal.put("doctorId", session.getPendingDoctorId());
        proposal.put("date", session.getPendingDate());
        proposal.put("time", session.getPendingTime());
        proposal.put("appointmentId", session.getPendingAppointmentId());
        return proposal;
    }

    private ParsedCommand mergeTime(ParsedCommand base, ParsedCommand hint) {
        return new ParsedCommand(base.intent(), base.specialty(), hint.timeFactors(),
                base.symptoms(), false, base.needsClarification(), base.unsupported(),
                base.frame(), base.engine() + "+rule-time");
    }

    private VoiceReply build(DialogueSession session, String reply, List<String> options,
                             Map<String, Object> appointment, Map<String, Object> proposal,
                             boolean handedOff, boolean unsupported,
                             String frame, String engine) {
        return new VoiceReply(session.getId(), reply, session.getStage(),
                !handedOff && session.getStage().startsWith("COLLECTING"),
                unsupported, handedOff, options, appointment, proposal,
                session.getExchanges(), props.getMaxExchanges(), frame, engine);
    }

    private enum Confirm { YES, NO, UNKNOWN }

    private static final Set<String> YES_WORDS = Set.of(
            "да", "ага", "угу", "ок", "окей", "хорошо", "верно", "подтверждаю",
            "правильно", "согласен", "согласна", "yes", "y", "+", "так точно",
            "все верно", "всё верно", "конечно");

    private static final Set<String> NO_WORDS = Set.of(
            "нет", "неверно", "не надо", "не нужно", "не хочу", "отмена", "отменить",
            "отказ", "стоп", "no", "n", "-", "не то", "другое");

    private static Confirm confirm(String text) {
        String q = ClinicalKnowledgeBase.normalize(text)
                .replaceAll("[^\\p{L}\\p{N}+\\- ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        for (String w : NO_WORDS) {
            if (matches(q, w)) {
                return Confirm.NO;
            }
        }
        for (String w : YES_WORDS) {
            if (matches(q, w)) {
                return Confirm.YES;
            }
        }
        return Confirm.UNKNOWN;
    }

    /** Короткие слова (да/нет/+-) сравниваем целиком, чтобы не путать с подстроками. */
    private static boolean matches(String q, String word) {
        if (word.length() <= 2) {
            return q.equals(word);
        }
        return q.equals(word) || q.contains(word);
    }
}
