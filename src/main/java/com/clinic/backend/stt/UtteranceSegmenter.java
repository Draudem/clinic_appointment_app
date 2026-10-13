package com.clinic.backend.stt;

/**
 * Порог отсечения тишины: границы фраз в потоке Asterisk.
 *
 * Реализация намеренно примитивная (RMS по окнам) — отдельная нейросетевая VAD-модель
 * для первого теста не нужна, а лишняя модель в памяти на сервере дорога.
 * Если позже понадобится устойчивее (шумная телефония, музыка), интерфейс останется
 * тем же — меняется только реализация.
 */
public final class UtteranceSegmenter {

    private final float silenceRmsThreshold;
    private final int windowSize;
    /** Диагностика границ фраз: java -Dstt.debug=true */
    private final boolean debug = Boolean.getBoolean("stt.debug");

    /** Накопленный речевой сигнал текущей фразы (16 кГц). */
    private float[] buffer = new float[16000 * 20];
    private int buffered;

    /** Окно тишины подряд, в отсчётах. */
    private int silenceRun;

    private boolean speechStarted;

    /**
     * Неполное окно, оставшееся от прошлого вызова.
     *
     * НУЖНО, потому что кадр AudioSocket — это 320 байт = 160 сэмплов на 8 кГц,
     * то есть ~319 сэмплов на 16 кГц. Это МЕНЬШЕ типичного окна в 480 сэмплов.
     * Без переноса остатка окно никогда не набрало бы полный размер, и анализ
     * энергии не выполнился бы ни разу за весь звонок.
     */
    private final float[] carry = new float[64 * 1024];
    private int carryLen;

    public UtteranceSegmenter(float silenceRmsThreshold, int windowSize) {
        this.silenceRmsThreshold = silenceRmsThreshold;
        this.windowSize = Math.max(1, Math.min(windowSize, carry.length));
    }

    /** Сброс — начало нового звонка. */
    public void reset() {
        buffered = 0;
        silenceRun = 0;
        speechStarted = false;
        carryLen = 0;
    }

    /**
     * Кормит кусок аудио. Возвращает true, если фраза завершилась и её надо распознать.
     * Само аудио забирается отдельно через {@link #takeUtterance()}.
     */
    public boolean feed(float[] chunk16k) {
        if (chunk16k == null || chunk16k.length == 0) {
            return false;
        }
        if (carryLen + chunk16k.length > carry.length) {
            // Остаток не помещается — сбрасываем его, чтобы не зациклиться.
            // Кадр в 320 байт сюда не попадает, это защита на случай аномального потока.
            carryLen = 0;
            return false;
        }
        // Склеиваем остаток прошлого вызова с текущим куском.
        System.arraycopy(chunk16k, 0, carry, carryLen, chunk16k.length);
        int total = carryLen + chunk16k.length;
        int usable = total - (total % windowSize);      // только целые окна

        boolean done = false;
        if (usable >= windowSize) {
            done = analyze(carry, 0, usable);
        }
        // Хвост сдвигаем в начало, чтобы следующий вызов дописал после него.
        int rem = total - usable;
        if (rem > 0) {
            System.arraycopy(carry, usable, carry, 0, rem);
        }
        carryLen = rem;
        return done;
    }

    /** Разбирает только целые окна в отрезке [offset, offset+usable). */
    private boolean analyze(float[] data, int offset, int usable) {
        for (int i = offset; i + windowSize <= offset + usable; i += windowSize) {
            double sum = 0;
            for (int j = 0; j < windowSize; j++) {
                double v = data[i + j];
                sum += v * v;
            }
            float rms = (float) Math.sqrt(sum / windowSize);

            if (rms >= silenceRmsThreshold) {
                silenceRun = 0;
                speechStarted = true;
                append(data, i, windowSize);
            } else if (speechStarted) {
                silenceRun += windowSize;
                // Хвост тишины полезен: модель получает естественную паузу в конце.
                append(data, i, windowSize);
                if (silenceRun >= silenceSize()) {
                    return true;                     // окно тишины полностью — фраза закончилась
                }
            }
            // Тишина до начала речи игнорируется, иначе в буфер попадёт мусор линии.
        }
        return false;
    }

    /** Сколько сэмптов тишины считается концом фразы. */
    private int silenceSize() {
        return Math.max(windowSize, (int) (16000 * silenceSeconds));
    }

    /** Порог конца фразы в секундах (для диагностики). */
    public float silenceSeconds = 1.2f;

    private void append(float[] src, int off, int len) {
        if (buffered + len > buffer.length) {
            // Буфер переполнен (очень длинная фраза) — обрезаем хвост, начало важнее.
            buffered = Math.max(0, buffer.length - len);
        }
        System.arraycopy(src, off, buffer, buffered, len);
        buffered += len;
    }

    /** Забирает накопленную фразу и очищает состояние. */
    public float[] takeUtterance() {
        float[] out = java.util.Arrays.copyOf(buffer, buffered);
        reset();
        return out;
    }

    /** Текущая длительность накопленного, секунды. */
    public float bufferedSeconds() {
        return buffered / 16000.0f;
    }

    /** Началась ли речь (нужно, чтобы не распознавать пустой буфер). */
    public boolean speechStarted() {
        return speechStarted;
    }
}