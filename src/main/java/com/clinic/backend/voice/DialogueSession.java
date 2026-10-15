package com.clinic.backend.voice;

import jakarta.persistence.*;

import java.time.Duration;
import java.time.Instant;

/**
 * Состояние голосового диалога. Хранится в БД, чтобы мобильное приложение
 * могло переподключиться и чтобы работал лимит обменов.
 *
 * Этап PENDING_CONFIRMATION держит предложенное действие (поля pending*):
 * запись выполняется ТОЛЬКО после явного подтверждения пациента.
 */
@Entity
@Table(name = "dialogue_sessions")
public class DialogueSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Логин пациента. Пустая строка — «ещё не установлен»: по телефону
     * аутентификации нет, логин появляется только после идентификации по ФИО.
     */
    @Column(nullable = false, length = 50)
    private String patientLogin;

    /**
     * Ключ телефонного звонка (UUID от Asterisk). По нему сессия находится
     * между репликами, поэтому она переживает паузы и переподключение.
     */
    @Column(name = "call_key", length = 60)
    private String callKey;

    /** Номер звонящего (ANI) — дополнительный признак при идентификации. */
    @Column(name = "caller_phone", length = 30)
    private String callerPhone;

    /** Момент последней реплики пациента — для отсчёта тишины (R-17). */
    @Column
    private Instant lastSpeechAt = Instant.now();

    @Column(nullable = false)
    private int exchanges;

    /**
     * Этап сценария С-01:
     * IDENTITY | SPECIALTY | OFFER | PENDING_CONFIRMATION | CONFIRMED |
     * RESCHEDULE | OPERATOR
     */
    @Column(nullable = false, length = 30)
    private String stage;

    @Column(length = 80)
    private String specialty;

    @Column(length = 255)
    private String pendingFrame;

    // --- Предложенное действие, ожидающее подтверждения ---
    @Column(length = 20)
    private String pendingAction;      // BOOK | RESCHEDULE

    private Long pendingDoctorId;

    @Column(length = 10)
    private String pendingDate;        // YYYY-MM-DD

    @Column(length = 5)
    private String pendingTime;        // HH:mm

    private Integer pendingCabinet;

    private Long pendingAppointmentId; // для RESCHEDULE: какую запись переносим

    @Column(length = 255)
    private String pendingSummary;     // человекочитаемое описание для вопроса

    // --- Идентификация пациента (регламент 3.5) ---

    @Column(length = 160)
    private String patientFio;

    @Column(length = 10)
    private String patientBirthDate;

    /** Ответ пациента на вопрос «вы уже записаны?» — ведёт в С-03 или обратно в С-01. */
    @Column(length = 20)
    private String dupDecision;

    // --- Предложенные слоты, чтобы «второй»/«в пятницу» что-то значили ---

    /** Слоты текущего предложения в компактном виде: doctorId|date|time|cabinet через запятую. */
    @Column(length = 512)
    private String offeredSlots;

    /** Пожелание пациента по дате, сохранённое до подбора слота. */
    @Column(length = 10)
    private String wantedDate;

    /** Пожелание пациента по времени, сохранённое до подбора слота. */
    @Column(length = 5)
    private String wantedTime;

    // --- Счётчики ---
    /**
     * Сколько раз ассистент не понял пациента подряд (лестница R-05).
     *
     * DEFAULT в columnDefinition обязателен: без него Hibernate пытается добавить
     * NOT NULL-колонку в таблицу, где уже есть строки, и миграция падает на живых базах.
     */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int missCount;

    /** Пациент отказался от записи разговора (3.4). */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean recordingOptOut;

    /**
     * Стенограмма разговора.
     *
     * Раньше здесь стоял @Lob вместе с columnDefinition="text": Hibernate читал
     * значение как large object (oid), а колонка была text — чтение падало с
     * «Unable to access lob stream», и GET /api/voice/sessions отдавал 500.
     * Для text Lob не нужен, поэтому убираем.
     *
     * Данные пациентов: по разделу 8 регламента стенограммы хранятся не более
     * 90 дней и доступны по ролям. Поле {@code expiresAt} позволяет чистить их
     * по расписанию, не разбирая содержимое.
     */
    @Column(columnDefinition = "text")
    private String transcript;

    /** Когда стенограмму пора удалить (90 дней с момента записи). */
    @Column
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean handedOff;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected DialogueSession() {
        // for JPA
    }

    public DialogueSession(String patientLogin) {
        this.patientLogin = patientLogin == null ? "" : patientLogin;
        this.stage = "SPECIALTY";
        this.exchanges = 0;
        this.transcript = "";
        this.expiresAt = Instant.now().plusSeconds(90L * 24 * 3600);
    }

    public void append(String role, String text) {
        this.transcript = (transcript == null ? "" : transcript) + role + ": " + text + "\n";
        this.updatedAt = Instant.now();
    }

    /** Считаем ТОЛЬКО реплики пациента (обмен = ответ пользователя). */
    public void incrementExchanges() {
        this.exchanges++;
        this.updatedAt = Instant.now();
    }

    /** Сброс счётчика при начале нового звонка. */
    public void setExchanges(int exchanges) {
        this.exchanges = exchanges;
        this.updatedAt = Instant.now();
    }

    public void clearPending() {
        this.pendingAction = null;
        this.pendingDoctorId = null;
        this.pendingDate = null;
        this.pendingTime = null;
        this.pendingCabinet = null;
        this.pendingAppointmentId = null;
        this.pendingSummary = null;
        this.offeredSlots = null;
        this.dupDecision = null;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getCallKey() {
        return callKey;
    }

    public void setCallKey(String callKey) {
        this.callKey = callKey;
    }

    public String getCallerPhone() {
        return callerPhone;
    }

    public void setCallerPhone(String callerPhone) {
        this.callerPhone = callerPhone;
    }

    public Instant getLastSpeechAt() {
        return lastSpeechAt;
    }

    public void touch() {
        this.lastSpeechAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** Сколько секунд прошло с последней реплики пациента. */
    public long silentSeconds() {
        return Duration.between(lastSpeechAt, Instant.now()).getSeconds();
    }

    public String getPatientLogin() {
        return patientLogin;
    }

    public void setPatientLogin(String patientLogin) {
        this.patientLogin = patientLogin;
    }

    public int getExchanges() {
        return exchanges;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
        this.updatedAt = Instant.now();
    }

    public String getSpecialty() {
        return specialty;
    }

    public void setSpecialty(String specialty) {
        this.specialty = specialty;
    }

    public String getPendingFrame() {
        return pendingFrame;
    }

    public void setPendingFrame(String pendingFrame) {
        this.pendingFrame = pendingFrame;
    }

    public String getPendingAction() {
        return pendingAction;
    }

    public void setPendingAction(String pendingAction) {
        this.pendingAction = pendingAction;
    }

    public Long getPendingDoctorId() {
        return pendingDoctorId;
    }

    public void setPendingDoctorId(Long pendingDoctorId) {
        this.pendingDoctorId = pendingDoctorId;
    }

    public String getPendingDate() {
        return pendingDate;
    }

    public void setPendingDate(String pendingDate) {
        this.pendingDate = pendingDate;
    }

    public String getPendingTime() {
        return pendingTime;
    }

    public void setPendingTime(String pendingTime) {
        this.pendingTime = pendingTime;
    }

    public Integer getPendingCabinet() {
        return pendingCabinet;
    }

    public void setPendingCabinet(Integer pendingCabinet) {
        this.pendingCabinet = pendingCabinet;
    }

    public Long getPendingAppointmentId() {
        return pendingAppointmentId;
    }

    public void setPendingAppointmentId(Long pendingAppointmentId) {
        this.pendingAppointmentId = pendingAppointmentId;
    }

    public String getPendingSummary() {
        return pendingSummary;
    }

    public void setPendingSummary(String pendingSummary) {
        this.pendingSummary = pendingSummary;
    }

    public String getPatientFio() {
        return patientFio;
    }

    public void setPatientFio(String patientFio) {
        this.patientFio = patientFio;
    }

    public String getPatientBirthDate() {
        return patientBirthDate;
    }

    public void setPatientBirthDate(String patientBirthDate) {
        this.patientBirthDate = patientBirthDate;
    }

    public String getDupDecision() {
        return dupDecision;
    }

    public void setDupDecision(String dupDecision) {
        this.dupDecision = dupDecision;
    }

    public String getOfferedSlots() {
        return offeredSlots;
    }

    public void setOfferedSlots(String offeredSlots) {
        this.offeredSlots = offeredSlots;
    }

    public String getWantedDate() {
        return wantedDate;
    }

    public void setWantedDate(String wantedDate) {
        this.wantedDate = wantedDate;
    }

    public String getWantedTime() {
        return wantedTime;
    }

    public void setWantedTime(String wantedTime) {
        this.wantedTime = wantedTime;
    }

    public int getMissCount() {
        return missCount;
    }

    public void resetMisses() {
        this.missCount = 0;
    }

    public void increaseMisses() {
        this.missCount++;
    }

    public boolean isRecordingOptOut() {
        return recordingOptOut;
    }

    public void setRecordingOptOut(boolean recordingOptOut) {
        this.recordingOptOut = recordingOptOut;
    }

    public String getTranscript() {
        return transcript;
    }

    /**
     * Полный сброс к началу нового звонка.
     *
     * Заодно забывается ЛИЧНОСТЬ: иначе следующий звонок того же транка начинал бы
     * с уже найденного пациента, и ассистент обращался бы по имени тому, кто его не называл.
     */
    public void resetForNewCall() {
        this.stage = "SPECIALTY";
        this.specialty = null;
        // patientLogin в БД NOT NULL: телефонный звонок не аутентифицирован,
        // поэтому «не найден» храним пустой строкой, а не null
        this.patientLogin = "";
        this.patientFio = null;
        this.patientBirthDate = null;
        this.pendingAction = null;
        this.pendingDoctorId = null;
        this.pendingDate = null;
        this.pendingTime = null;
        this.pendingCabinet = null;
        this.pendingAppointmentId = null;
        this.pendingSummary = null;
        this.pendingFrame = null;
        this.offeredSlots = null;
        this.wantedDate = null;
        this.wantedTime = null;
        this.dupDecision = null;
        this.exchanges = 0;
        this.missCount = 0;
        this.handedOff = false;
        this.recordingOptOut = false;
        this.transcript = "";
        this.updatedAt = Instant.now();
    }

    /** Очистить стенограмму — например, при начале нового звонка. */
    public void setTranscript(String transcript) {
        this.transcript = transcript;
        this.updatedAt = Instant.now();
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isHandedOff() {
        return handedOff;
    }

    public void setHandedOff(boolean handedOff) {
        this.handedOff = handedOff;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}