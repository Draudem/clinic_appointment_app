package com.clinic.backend.stt;

/**
 * Заглушка синтеза: возвращает тишину нужной длительности.
 *
 * Нужна, когда модель Piper не загрузилась или синтез выключен. Звонок при этом
 * не обрывается: звонящий слышит тишину, а сервер продолжает принимать аудио и
 * распознавать речь — этого достаточно, чтобы проверить связь и распознавание.
 *
 * Длительность тишины пропорциональна длине текста, чтобы пауза выглядела
 * осмысленной, а не мгновенным щелчком.
 */
public class SilentReplySynthesizer implements ReplySynthesizer {

    private static final int SAMPLE_RATE = 8000;
    private static final int MS_PER_CHAR = 45;    // ~13 символов в секунду
    private static final int MAX_MS = 8000;

    @Override
    public float[] synthesize(String text) {
        if (text == null || text.isBlank()) {
            return new float[0];
        }
        int ms = Math.min(MAX_MS, Math.max(300, text.length() * MS_PER_CHAR));
        return new float[SAMPLE_RATE * ms / 1000];
    }

    @Override
    public int sampleRate() {
        return SAMPLE_RATE;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String name() {
        return "silent-stub";
    }
}