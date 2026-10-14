package com.clinic.backend.stt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки синтеза речи (app.tts.*).
 *
 * Голос отключается флагом enabled или пропадает автоматически, если модель не
 * найдена — тогда звонки всё равно принимаются и распознаются, просто звонящий
 * слышит тишину (для проверки связи этого достаточно).
 */
@ConfigurationProperties(prefix = "app.tts")
public class TtsProperties {

    private boolean enabled = true;

    /** Каталог модели Piper: ru_RU-irina-medium.onnx + tokens.txt + espeak-ng-data. */
    private String modelDir = "models/tts/vits-piper-ru_RU-irina-medium-int8";

    private int numThreads = 2;

    /** Темп речи. 1.0 — обычный, меньше — медленнее, больше — быстрее. */
    private float speed = 1.0f;

    /** Фраза при подключении звонящего. */
    private String greeting =
            "Для записи на приём сообщите следующую информацию: "
            + "специальность или симптомы, желаемую дату и время.";

    /** Ответ после того, как клиент замолчал и его фраза распознана. */
    private String replyOnSilence = "Спасибо, записываю.";

    /**
     * Сколько секунд ждать конца озвучивания перед закрытием соединения.
     * Даём запас, чтобы фраза успела дозвучать с запасом.
     */
    private int closeDelaySeconds = 2;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

    public float getSpeed() {
        return speed;
    }

    public void setSpeed(float speed) {
        this.speed = speed;
    }

    public String getGreeting() {
        return greeting;
    }

    public void setGreeting(String greeting) {
        this.greeting = greeting;
    }

    public String getReplyOnSilence() {
        return replyOnSilence;
    }

    public void setReplyOnSilence(String replyOnSilence) {
        this.replyOnSilence = replyOnSilence;
    }

    public int getCloseDelaySeconds() {
        return closeDelaySeconds;
    }

    public void setCloseDelaySeconds(int closeDelaySeconds) {
        this.closeDelaySeconds = closeDelaySeconds;
    }
}