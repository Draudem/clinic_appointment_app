package com.clinic.backend.voice;

import com.clinic.backend.identity.PatientIdentity;
import com.clinic.backend.identity.PatientIdentityResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Фасад диалога: держит сессию, определяет личность пациента и передаёт ход
 * в сценарий {@link BookingFlow}.
 *
 * Класс намеренно тонкий. Вся логика записи — в сценарии, вся «математика
 * времени» — в {@link TimeResolver}, подбор слота — в {@link SlotResolver},
 * разбор реплики — в {@link CommandParser}, расписание — за портом
 * {@link com.clinic.backend.schedule.ScheduleProvider}. Замена любого из них
 * на заглушку или на более сильную модель не требует правок здесь.
 *
 * Три входа — HTTP (/api/voice/command), телефон (AudioSocket) и мобильное
 * приложение — обслуживаются одними и теми же методами, поэтому сценарий
 * не может «разойтись» между каналами.
 */
@Service
public class DialogueManager {

    private static final Logger log = LoggerFactory.getLogger(DialogueManager.class);

    private final BookingFlow flow;
    private final DialogueSessionRepository sessions;
    private final PatientIdentityResolver identity;
    private final VoiceProperties props;

    public DialogueManager(BookingFlow flow,
                           DialogueSessionRepository sessions,
                           PatientIdentityResolver identity,
                           VoiceProperties props) {
        this.flow = flow;
        this.sessions = sessions;
        this.identity = identity;
        this.props = props;
    }

    // ------------------------------------------------------------------
    // Телефон
    // ------------------------------------------------------------------

    /**
     * Начало телефонного звонка: обнуляем состояние прошлого разговора.
     *
     * Идентификатор звонка у Asterisk может повторяться (переподключение, повторный
     * дозвон с того же транка). Без сброса новый звонок продолжал старый: лимит
     * обменов копился, и через несколько неудачных попыток ассистент переводил
     * в оператора уже на первой фразе.
     */
    @Transactional
    public void startCall(String callKey, String ani) {
        DialogueSession session = sessions.findByCallKey(callKey).orElse(null);
        if (session == null) {
            session = new DialogueSession("");
            session.setCallKey(callKey);
        }
        session.setCallerPhone(ani);
        session.resetForNewCall();
        session.touch();
        sessions.save(session);
    }

    /**
     * Ход телефонного разговора.
     *
     * Сессия ищется по ключу звонка, поэтому несколько фраз подряд ведут
     * один диалог. Номер звонящего — только дополнительный признак для
     * идентификации, никогда единственный (регламент 3.5).
     */
    @Transactional
    public Turn handleByCall(String callKey, String ani, String text) {
        DialogueSession session = sessions.findByCallKey(callKey).orElseGet(() -> {
            DialogueSession s = new DialogueSession("");
            s.setCallKey(callKey);
            s.setCallerPhone(ani);
            return s;
        });
        if (ani != null && !ani.isBlank() && !ani.equals(session.getCallerPhone())) {
            session.setCallerPhone(ani);
        }
        session.touch();
        // лимит обменов (защита от зацикливания) — до того, как что-то предпримем
        if (session.getExchanges() > props.getMaxExchanges() && !session.isHandedOff()) {
            return transferForLimit(session);
        }
        Turn turn = flow.handle(session, whoFor(session, ani), text);
        sessions.save(session);
        return turn;
    }

    /** Пациент заговорил — сбрасываем счётчик тишины. */
    @Transactional
    public Turn touchCall(String callKey) {
        sessions.findByCallKey(callKey).ifPresent(DialogueSession::touch);
        return Turn.continueWith(null, "WAITING", List.of(), 0);
    }

    /** Тишина в трубке. Реакция по правилу R-17. */
    @Transactional
    public Turn silence(String callKey) {
        DialogueSession session = sessions.findByCallKey(callKey).orElse(null);
        long seconds = session == null ? 0 : session.silentSeconds();
        if (seconds < 5) {
            return Turn.continueWith(null, "WAITING", List.of(), 0);
        }
        if (seconds < 10) {
            return Turn.continueWith("Вы меня слышите?", "WAITING", List.of(), 0);
        }
        session.setStage("CLOSED");
        session.append("ассистент", "Вас не слышно. Перезвоните, пожалуйста.");
        sessions.save(session);
        return Turn.hangup("Вас не слышно. Перезвоните, пожалуйста.", "CLOSED", "DROPPED", 0);
    }

    /** Кто говорит по телефону: сначала ANI как подсказка, иначе — по названным данным. */
    private PatientIdentity whoFor(DialogueSession session, String ani) {
        if (session.getPatientLogin() != null && !session.getPatientLogin().isBlank()) {
            return identity.findByLogin(session.getPatientLogin()).orElseGet(PatientIdentity::unknown);
        }
        if (ani != null && !ani.isBlank()) {
            var byPhone = identity.findByPhone(ani);
            if (byPhone.isPresent()) {
                session.setPatientLogin(byPhone.get().login());
                return byPhone.get();
            }
        }
        return PatientIdentity.unknown();
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    @Transactional
    public Turn handle(String login, Long sessionId, String text) {
        DialogueSession session = resolveHttpSession(login, sessionId);
        if (session.getExchanges() > props.getMaxExchanges() && !session.isHandedOff()) {
            return transferForLimit(session);
        }
        Turn turn = flow.handle(session, whoForHttp(login), text);
        sessions.save(session);
        return turn;
    }

    /** Ход по HTTP с ответом в существующем контракте. */
    @Transactional
    public VoiceReply handleForApi(String login, Long sessionId, String text) {
        DialogueSession session = resolveHttpSession(login, sessionId);
        Turn turn = flow.handle(session, whoForHttp(login), text);
        sessions.save(session);
        return toReply(session, turn);
    }

    private DialogueSession resolveHttpSession(String login, Long sessionId) {
        if (sessionId != null) {
            var existing = sessions.findById(sessionId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        return new DialogueSession(login);
    }

    /** По HTTP пациент уже аутентифицирован: берём ФИО и дату рождения из реестра. */
    private PatientIdentity whoForHttp(String login) {
        if (login == null || login.isBlank()) {
            return PatientIdentity.unknown();
        }
        return identity.findByLogin(login).orElseGet(PatientIdentity::unknown);
    }

    // ------------------------------------------------------------------
    // Общее
    // ------------------------------------------------------------------

    private Turn transferForLimit(DialogueSession session) {
        session.setHandedOff(true);
        session.setStage("OPERATOR");
        String reply = "Соединяю с сотрудником — суть вашего вопроса уже передана.";
        session.append("ассистент", reply);
        sessions.save(session);
        return Turn.transfer(reply, "R-05", "ESCALATED", session.getMissCount());
    }

    /**
     * Превращает ход сценария в ответ существующего HTTP-контракта.
     *
     * <p>Помимо {@code reply}/{@code stage}/{@code appointment} теперь заполняются
     * {@code resultCode}, {@code status} и {@code confidence} — чтобы и пациент,
     * и интегратор, и автотест видели результат без разбора вложенных полей.
     */
    VoiceReply toReply(DialogueSession session, Turn turn) {
        Map<String, Object> appointment = null;
        if ("BOOKED".equals(turn.resultCode()) || "RESCHEDULED".equals(turn.resultCode())) {
            appointment = bookedAppointment(session);
        }
        Map<String, Object> proposal = new LinkedHashMap<>();
        if (session.getPendingDoctorId() != null) {
            proposal.put("action", session.getPendingAction());
            proposal.put("doctorId", session.getPendingDoctorId());
            proposal.put("date", session.getPendingDate());
            proposal.put("time", session.getPendingTime());
            proposal.put("cabinet", session.getPendingCabinet());
            proposal.put("appointmentId", session.getPendingAppointmentId());
            proposal.put("summary", session.getPendingSummary());
        }
        List<String> options = turn.options().stream().map(Turn.Slot::spoken).toList();
        return new VoiceReply(
                session.getId(),
                turn.reply(),
                turn.stage(),
                turn.action() == Turn.Action.CONTINUE,
                false,
                turn.action() == Turn.Action.TRANSFER,
                options,
                appointment,
                proposal,
                session.getExchanges(),
                props.getMaxExchanges(),
                session.getPendingFrame(),
                "booking-flow",
                turn.resultCode(),
                statusOf(turn),
                confidenceOf(turn));
    }

    /**
     * Короткий статус для UI: BOOKED | PENDING | ESCALATED | INFO.
     *
     * <p>Это то же самое, что {@code resultCode} из {@link Turn}, но приведённое
     * к четырём значениям, которые удобно показывать в мобильном приложении
     * и в административной панели. Полный {@code resultCode} остаётся рядом:
     * он тоньше (BOOKED, RESCHEDULED, CANCELLED, INFO_GIVEN, ESCALATED, EMERGENCY,
     * COMPLAINT, WAITLISTED, HOME_VISIT_REQUESTED), но для быстрого взгляда
     * достаточно четырёх групп.
     */
    private static String statusOf(Turn turn) {
        if (turn.action() == Turn.Action.TRANSFER) {
            return "ESCALATED";
        }
        if ("BOOKED".equals(turn.resultCode())
                || "RESCHEDULED".equals(turn.resultCode())
                || "CANCELLED".equals(turn.resultCode())
                || "CONFIRMED".equals(turn.resultCode())) {
            return "BOOKED";
        }
        if (turn.action() == Turn.Action.CONTINUE) {
            // сценарий ждёт подтверждения или следующей реплики
            return "PENDING_CONFIRMATION".equals(turn.stage()) ? "PENDING" : "INFO";
        }
        // HANGUP без явного BOOKED — это справка/отказ, а не запись
        return "INFO_GIVEN".equals(turn.resultCode()) ? "INFO" : "DONE";
    }

    /**
     * Уверенность ассистента, 0.0…1.0.
     *
     * <p>Не «магическое число»: вычисляется из состояния, а не задаётся константой.
     * Правило простое и объяснимое:
     * <ul>
     *   <li>запись создана/изменена → 0.95 (действие в МИС подтверждено);</li>
     *   <li>справка выдана (INFO_GIVEN) → 0.9 (источник — БД/расписание, не МИС);</li>
     *   <li>ожидание подтверждения → 0.8 (ассистент уверен в предложении, но пациент
     *       ещё не сказал «да»);</li>
     *   <li>перевод оператору → 0.5 (честно: ассистент не справился сам);</li>
     *   <li>пустой результат → 0.3 (защита от деления на ноль в клиентских формулах).</li>
     * </ul>
     *
     * <p>Точность распознавания речи в это число не входит: STT не отдаёт
     * вероятность, а подмешивать сюда «на глаз» — значит сделать число
     * неинтерпретируемым. Если понадобится — расширим {@link Turn} полем
     * {@code sttConfidence} и объединим явно.
     */
    private static double confidenceOf(Turn turn) {
        if (turn.action() == Turn.Action.TRANSFER) {
            return 0.5;
        }
        if ("BOOKED".equals(turn.resultCode())
                || "RESCHEDULED".equals(turn.resultCode())
                || "CANCELLED".equals(turn.resultCode())
                || "CONFIRMED".equals(turn.resultCode())) {
            return 0.95;
        }
        if ("INFO_GIVEN".equals(turn.resultCode())) {
            return 0.9;
        }
        if (turn.action() == Turn.Action.CONTINUE) {
            return "PENDING_CONFIRMATION".equals(turn.stage()) ? 0.8 : 0.75;
        }
        return 0.3;
    }

    /** Последняя созданная запись пациента — для ответа «вы записаны». */
    private Map<String, Object> bookedAppointment(DialogueSession session) {
        if (session.getPatientLogin() == null || session.getPatientLogin().isBlank()) {
            return null;
        }
        return flow.lastBooking(session.getPatientLogin());
    }

    @Transactional
    public Turn startReminder(String callKey, Long appointmentId) {
        DialogueSession session = new DialogueSession("");
        session.setCallKey(callKey);
        session.setPendingAction("REMINDER");
        session.setPendingAppointmentId(appointmentId);
        session.setStage("REMINDER_CONFIRMATION");
        session.resetForNewCall(); // сбрасываем старое
        session.setPendingAppointmentId(appointmentId); // возвращаем после сброса
        session.setStage("REMINDER_CONFIRMATION");
        session.setPendingAction("REMINDER");

        sessions.save(session);

        // Возвращаем Turn, который скажет "Здравствуйте! Напоминаю вам о записи..."
        // Но так как мы пока не загружаем детали записи из МИС, скажем общее приветствие.
        String reply = "Здравствуйте! Это виртуальный помощник поликлиники номер семь. Напоминаю о вашей записи на завтра. Вы придёте?";
        session.append("ассистент", reply);
        return Turn.continueWith(reply, "REMINDER_CONFIRMATION", List.of(), 0);
    }

    /** Удаляет стенограммы старше срока хранения (раздел 8 регламента: не более 90 дней). */
    @Transactional
    public int purgeExpiredTranscripts() {
        var expired = sessions.findByExpiresAtBefore(java.time.Instant.now());
        for (DialogueSession s : expired) {
            sessions.delete(s);
        }
        if (!expired.isEmpty()) {
            log.info("Удалено стенограмм по истечении срока хранения: {}", expired.size());
        }
        return expired.size();
    }

    /** Сколько тишины в трубке — для диагностики звонка. */
    public static Duration silentFor(DialogueSession session) {
        return Duration.ofSeconds(session.silentSeconds());
    }
}