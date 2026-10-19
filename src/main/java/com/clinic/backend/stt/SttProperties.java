package com.clinic.backend.stt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки серверного STT (app.stt.*).
 *
 * Выключается флагом enabled — тогда AudioSocket-сервер не поднимается,
 * а бэкенд работает ровно как раньше (STT на устройстве мобильного приложения).
 */
@ConfigurationProperties(prefix = "app.stt")
public class SttProperties {

    private boolean enabled = true;

    /**
     * Открытый доступ к распознанным фразам без JWT (только чтение) — для наладки телефонии.
     * Перед выкладкой наружу выключить: STT_PUBLIC_READ=false.
     */
    private boolean publicRead = true;

    /** TCP-порт AudioSocket. Asterisk подключается сюда, а не на HTTP-порт. */
    private int audiosocketPort = 8091;

    /** Каталог с файлами модели GigaAM: model.int8.onnx и tokens.txt. */
    private String modelDir = "models/stt/sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19";

    private int numThreads = 2;

    /** Пауза тишины, которая считается концом фразы, секунды. */
    private double silenceSeconds = 1.2;

    /** Порог энергии (RMS) ниже которого считаем тишиной. */
    private float silenceRmsThreshold = 0.012f;

    /** Максимальная длина фразы, после неё форсируем распознавание, секунды. */
    private double maxUtteranceSeconds = 20.0;

    /** Минимальная длительность речи, короче которой не распознаём (шум/щелчок), секунды. */
    private double minUtteranceSeconds = 0.3;

    /**
     * Отдавать ли ответ голосом обратно в сокет.
     * false — в сокет уходит тишина, текст забирают по HTTP (текущий режим: TTS-заглушка).
     */
    private boolean speakReplies = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isPublicRead() {
        return publicRead;
    }

    public void setPublicRead(boolean publicRead) {
        this.publicRead = publicRead;
    }

    public int getAudiosocketPort() {
        return audiosocketPort;
    }

    public void setAudiosocketPort(int audiosocketPort) {
        this.audiosocketPort = audiosocketPort;
    }

    public String getModelDir() {
        return modelDir;
    }

    public void setModelDir(String modelDir) {
        this.modelDir = modelDir;
    }

    public int getNumThreads() {
        return numThreads;
    }

    public void setNumThreads(int numThreads) {
        this.numThreads = numThreads;
    }

    public double getSilenceSeconds() {
        return silenceSeconds;
    }

    public void setSilenceSeconds(double silenceSeconds) {
        this.silenceSeconds = silenceSeconds;
    }

    public float getSilenceRmsThreshold() {
        return silenceRmsThreshold;
    }

    public void setSilenceRmsThreshold(float silenceRmsThreshold) {
        this.silenceRmsThreshold = silenceRmsThreshold;
    }

    public double getMaxUtteranceSeconds() {
        return maxUtteranceSeconds;
    }

    public void setMaxUtteranceSeconds(double maxUtteranceSeconds) {
        this.maxUtteranceSeconds = maxUtteranceSeconds;
    }

    public double getMinUtteranceSeconds() {
        return minUtteranceSeconds;
    }

    public void setMinUtteranceSeconds(double minUtteranceSeconds) {
        this.minUtteranceSeconds = minUtteranceSeconds;
    }

    public boolean isSpeakReplies() {
        return speakReplies;
    }

    public void setSpeakReplies(boolean speakReplies) {
        this.speakReplies = speakReplies;
    }

    /**
     * Включать ли подробную диагностику аудио (AUDIO_RX, VAD, TTS_STATE).
     * По умолчанию false: в продакшене логи должны быть чистыми.
     */
    private boolean debugAudio = false;

    public boolean isDebugAudio() {
        return debugAudio;
    }

    public void setDebugAudio(boolean debugAudio) {
        this.debugAudio = debugAudio;
    }
}