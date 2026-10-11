package com.clinic.backend.voice;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Состояние голосового диалога. Хранится в БД, чтобы мобильное приложение
 * могло переподключиться и чтобы работал лимит обменов (max exchanges ->
 * передача оператору).
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

    @Column(nullable = false)
    private int exchanges;

    /** COLLECTING_SPECIALTY | COLLECTING_TIME | PENDING_CONFIRMATION | CONFIRMED | OPERATOR */
    @Column(nullable = false, length = 30)
    private String stage;

    @Column(length = 80)
    private String specialty;

    @Column(length = 255)
    private String pendingFrame;

    // --- Предложенное действие, ожидающее подтверждения ---
    @Column(length = 20)
    private String pendingAction;      // BOOK | CANCEL

    private Long pendingDoctorId;

    @Column(length = 10)
    private String pendingDate;        // YYYY-MM-DD

    @Column(length = 5)
    private String pendingTime;        // HH:mm

    private Long pendingAppointmentId; // для CANCEL

    @Column(length = 255)
    private String pendingSummary;     // человекочитаемое описание для вопроса

    @Lob
    @Column(columnDefinition = "text")
    private String transcript;

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
        this.patientLogin = patientLogin;
        this.stage = "COLLECTING_SPECIALTY";
        this.exchanges = 0;
        this.transcript = "";
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

    public void clearPending() {
        this.pendingAction = null;
        this.pendingDoctorId = null;
        this.pendingDate = null;
        this.pendingTime = null;
        this.pendingAppointmentId = null;
        this.pendingSummary = null;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getPatientLogin() {
        return patientLogin;
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

    public String getTranscript() {
        return transcript;
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
