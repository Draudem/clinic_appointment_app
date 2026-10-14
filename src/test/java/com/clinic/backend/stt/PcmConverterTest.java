package com.clinic.backend.stt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Понижение частоты для исходящего аудио: синтез даёт 22050 Гц,
 * а протокол AudioSocket требует ровно 8000.
 */
class PcmConverterTest {

    private static final int SRC = 22050;
    private static final int DST = 8000;

    @Test
    void длинаСоответствуетНовойЧастоте() {
        float[] in = new float[SRC];          // ровно 1 секунда
        float[] out = PcmConverter.downsample(in, SRC, DST);
        assertEquals(DST, out.length, "секунда должна стать секундой");
    }

    @Test
    void пустойВходНеЛомает() {
        assertEquals(0, PcmConverter.downsample(new float[0], SRC, DST).length);
    }

    @Test
    void одинаковыеЧастотыВозвращаютИсходникКакЕсть() {
        float[] in = {0.1f, 0.2f, 0.3f};
        float[] out = PcmConverter.downsample(in, 8000, 8000);
        assertEquals(3, out.length);
        assertEquals(0.2f, out[1], 1e-6f);
    }

    @Test
    void повышениеЧастотыОтклоняетсяЯвно() {
        // За это отвечает PcmResampler (8->16 кГц), тут только понижение.
        assertThrows(IllegalArgumentException.class,
                () -> PcmConverter.downsample(new float[10], 8000, 22050));
    }

    @Test
    void фильтрОставляетНизкиеЧастотыБезЗаметногоЗатухания() {
        // Низкочастотный тон (200 Гц) обязан выжить фильтр почти без потерь:
        // речь держится именно на низких частотах, телефонный канал их режет.
        int n = SRC;
        float[] tone = new float[n];
        for (int i = 0; i < n; i++) {
            tone[i] = (float) Math.sin(2 * Math.PI * 200.0 * i / SRC);
        }
        float[] out = PcmConverter.downsample(tone, SRC, DST);

        // RMS измеряем по середине, чтобы не учитывать края свёртки
        int from = DST / 4;
        int to = DST - DST / 4;
        double srcE = rms(tone, SRC / 4, n - SRC / 4);
        double dstE = rms(out, from, to);
        assertTrue(dstE > srcE * 0.8,
                "тон 200 Гц должен сохраниться, получено " + dstE + " против " + srcE);
    }

    @Test
    void фильтрГаситВысокиеЧастоты() {
        // Без этого голос пищит: то, что выше 4 кГц, упало бы в низкие частоты.
        int n = SRC;
        float[] tone = new float[n];
        for (int i = 0; i < n; i++) {
            tone[i] = (float) Math.sin(2 * Math.PI * 6000.0 * i / SRC);
        }
        float[] out = PcmConverter.downsample(tone, SRC, DST);

        int from = DST / 4;
        int to = DST - DST / 4;
        double srcE = rms(tone, SRC / 4, n - SRC / 4);
        double dstE = rms(out, from, to);
        assertTrue(dstE < srcE * 0.5,
                "тон 6 кГц должен быть подавлен, получено " + dstE + " против " + srcE);
    }

    @Test
    void ядроНормировано() {
        float[] k = PcmConverter.lowPassKernel(3600, SRC, 32);
        double sum = 0;
        for (float v : k) {
            sum += v;
        }
        assertEquals(1.0, sum, 1e-5, "сумма коэффициентов фильтра должна быть единицей");
    }

    private static double rms(float[] a, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) {
            s += a[i] * a[i];
        }
        return Math.sqrt(s / (to - from));
    }
}