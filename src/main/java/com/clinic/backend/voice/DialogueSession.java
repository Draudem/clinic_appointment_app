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

    @Column(nullable = false, length = 50)
    private String patientLogin;

    @Column(name = "call_key", length = 60)
    private String callKey;

    @Column(name = "caller_phone", length = 30)
    private String callerPhone;

    @Column
    private Instant lastSpeechAt = Instant.now();

    @Column(nullable = false)
    private int exchanges;

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

    /** ФИО врача, нужно для маппинга ID из Excel на ID стенда МИС. */
    @Column(length = 160)
    private String pendingDoctorFio;

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

    @Column(length = 20)
    private String dupDecision;

    // --- Предложенные слоты ---

    @Column(length = 512)
    private String offeredSlots;

    @Column(length = 10)
    private String wantedDate;

    @Column(length = 5)
    private String wantedTime;

    // --- Счётчики ---
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int missCount;

    @Column(length = 255)
    private String saidFio;

    @Column(length = 10)
    private String saidBirthDate;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int identityMisses;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean recordingOptOut;

    @Column(columnDefinition = "text")
    private String transcript;

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

    public void incrementExchanges() {
        this.exchanges++;
        this.updatedAt = Instant.now();
    }

    public void setExchanges(int exchanges) {
        this.exchanges = exchanges;
        this.updatedAt = Instant.now();
    }

    public void clearPending() {
        this.pendingAction = null;
        this.pendingDoctorId = null;
        this.pendingDoctorFio = null; // Очищаем ФИО
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

    public String getPendingDoctorFio() {
        return pendingDoctorFio;
    }

    public void setPendingDoctorFio(String pendingDoctorFio) {
        this.pendingDoctorFio = pendingDoctorFio;
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

    public String getSaidFio() {
        return saidFio;
    }

    public void setSaidFio(String saidFio) {
        this.saidFio = saidFio;
    }

    public String getSaidBirthDate() {
        return saidBirthDate;
    }

    public void setSaidBirthDate(String saidBirthDate) {
        this.saidBirthDate = saidBirthDate;
    }

    public int getIdentityMisses() {
        return identityMisses;
    }

    public void setIdentityMisses(int identityMisses) {
        this.identityMisses = identityMisses;
    }

    public void resetIdentity() {
        this.saidFio = null;
        this.saidBirthDate = null;
        this.identityMisses = 0;
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
     */
    public void resetForNewCall() {
        this.stage = "SPECIALTY";
        this.specialty = null;
        this.patientLogin = "";
        this.patientFio = null;
        this.patientBirthDate = null;
        this.saidFio = null;
        this.saidBirthDate = null;
        this.identityMisses = 0;
        this.pendingAction = null;
        this.pendingDoctorId = null;
        this.pendingDoctorFio = null; // Очищаем ФИО
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