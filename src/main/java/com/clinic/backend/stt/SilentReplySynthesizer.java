package com.clinic.backend.stt;

/**
 * Заглушка синтеза: возвращает тишину вместо речи.
 *
 * Зачем она нужна уже сейчас: Asterisk ждёт ответа в сокет, и если не отправить
 * ничего, он может посчитать вызов зависшим. Ровно тишины достаточно, чтобы
 * соединение жило, а текст при этом забирается по HTTP — так работает первый тест.
 *
 * Длительность тишины пропорциональна длине текста (по грубой оценке 12 мс на символ),
 * чтобы напарник слышал паузу, пропорциональную объёму ответа.
 */
public class SilentReplySynthesizer implements ReplySynthesizer {

    private static final int SAMPLE_RATE = 16000;
    private static final int MS_PER_CHAR = 12;
    private static final int MAX_MS = 4000;

    @Override
    public float[] synthesize(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int ms = Math.min(MAX_MS, Math.max(300, text.length() * MS_PER_CHAR));
        return new float[SAMPLE_RATE * ms / 1000];
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