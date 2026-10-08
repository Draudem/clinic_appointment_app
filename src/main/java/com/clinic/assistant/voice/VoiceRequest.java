package com.clinic.assistant.voice;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Журнал голосовых запросов: аудио, транскрипт, разобранный фрейм, статус.
 * Полезно для отладки, аналитики и «переигрывания» запросов без микрофона.
 */
@Entity
@Table(name = "voice_request")
public class VoiceRequest {

    public enum Status {
        /** Успешно разобран и подобраны варианты записи. */
        OK,
        /** Не удалось распознать/разобрать. */
        FAILED,
        /** Ошибка ИИ-этапа (модель недоступна). */
        AI_ERROR
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private com.clinic.assistant.user.User user;

    @Column(name = "audio_path", length = 255)
    private String audioPath;

    @Column(name = "content_type", length = 64)
    private String contentType;

    @Column(columnDefinition = "text")
    private String transcript;

    @Column(name = "frame_json", columnDefinition = "text")
    private String frameJson;

    @Column(length = 16)
    private String intent;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(length = 512)
    private String error;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected VoiceRequest() {
    }

    public VoiceRequest(com.clinic.assistant.user.User user) {
        this.user = user;
    }

    public Long getId() {
        return id;
    }

    public com.clinic.assistant.user.User getUser() {
        return user;
    }

    public String getAudioPath() {
        return audioPath;
    }

    public void setAudioPath(String audioPath) {
        this.audioPath = audioPath;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getTranscript() {
        return transcript;
    }

    public void setTranscript(String transcript) {
        this.transcript = transcript;
    }

    public String getFrameJson() {
        return frameJson;
    }

    public void setFrameJson(String frameJson) {
        this.frameJson = frameJson;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
