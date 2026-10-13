package com.clinic.backend.stt;

import java.util.Arrays;

/**
 * Пересчёт частоты дискретизации для телефонии.
 *
 * Asterisk AudioSocket отдаёт PCM строго в 8 кГц (by design, параметр не настраивается),
 * а GigaAM обучена на 16 кГц. Без пересчёта модель получает «вдвое медленную» речь
 * и выдаёт мусор или тишину.
 *
 * Линейная интерполяция: на каждую пару отсчётов 8 кГц приходятся два отсчёта 16 кГц —
 * исходный и середина между ним и следующим. Состояние (последний отсчёт) живёт между
 * вызовами, поэтому стыки чанков (AudioSocket шлёт по ~320 байт) не дают щелчков.
 *
 * Формат входа и выхода: float[] в диапазоне [-1.0, 1.0] — так его принимает
 * sherpa-onnx (OfflineStream.acceptWaveform), без лишнего преобразования в горячем пути.
 */
public final class PcmResampler {

    private float lastSample = 0f;
    /** Есть ли предыдущий отсчёт, с которым можно интерполировать. */
    private boolean primed = false;

    /**
     * Пересчитывает порцию 8 кГц -> 16 кГц с сохранением состояния.
     *
     * <p>Состояние переживает вызовы, поэтому размер буфера считается по худшему случаю —
     * 2 отсчёта на каждый входной (это верно, когда {@code primed} уже установлен
     * предыдущим вызовом). Лишнее отсекается через copyOf.
     *
     * @param input8k отсчёты 8 кГц
     * @return отсчёты 16 кГц: 2n-1 для первого чанка и 2n для последующих
     */
    public float[] resample(float[] input8k) {
        if (input8k == null || input8k.length == 0) {
            return new float[0];
        }
        float[] out = new float[input8k.length * 2];
        int n = 0;

        for (float cur : input8k) {
            if (!primed) {
                out[n++] = cur;
                lastSample = cur;
                primed = true;
                continue;
            }
            out[n++] = lastSample;                                  // целый отсчёт
            out[n++] = lastSample + (cur - lastSample) * 0.5f;      // середина
            lastSample = cur;
        }
        return Arrays.copyOf(out, n);
    }

    /** Сброс состояния — в начале нового звонка или новой фразы. */
    public void reset() {
        primed = false;
        lastSample = 0f;
    }

    /**
     * Разовый пересчёт без сохранения состояния — для тестов и офлайн-разбора.
     */
    public static float[] once(float[] input8k) {
        return new PcmResampler().resample(input8k);
    }

    /**
     * PCM int16 little-endian (как приходит из сокета) -> float[] [-1, 1].
     * Длина округляется вниз до чётного числа байт: неполная пара игнорируется.
     */
    public static float[] bytesToFloat(byte[] bytes, int offset, int length) {
        int count = length / 2;
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            int lo = bytes[offset + i * 2] & 0xFF;
            int hi = bytes[offset + i * 2 + 1];                  // знаковый старший байт
            int v = (hi << 8) | lo;                               // little-endian
            out[i] = v / 32768.0f;
        }
        return out;
    }

    /** float[] [-1, 1] -> PCM int16 little-endian (формат, который ждёт Asterisk). */
    public static byte[] floatToBytes(float[] samples) {
        byte[] out = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            int v = Math.round(samples[i] * 32767f);
            if (v > 32767) {
                v = 32767;
            } else if (v < -32768) {
                v = -32768;
            }
            out[i * 2] = (byte) (v & 0xFF);                      // младший байт
            out[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);          // старший байт
        }
        return out;
    }
}