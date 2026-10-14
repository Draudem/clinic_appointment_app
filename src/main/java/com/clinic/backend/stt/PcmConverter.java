package com.clinic.backend.stt;

/**
 * Приведение PCM к произвольной частоте дискретизации.
 *
 * Нужно для синтеза: Piper выдаёт 22050 Гц, а протокол AudioSocket требует ровно
 * 8000 Гц (формат жёстко зашит в Asterisk).
 *
 * ПОЧЕМУ НЕЛЬЗЯ ПРОСТО ВЫБРАСИТЬ КАЖДЫЙ ТРЕТИЙ ОТСЧЁТ
 * -----------------------------------------------------
 * При прореживании без фильтра всё, что выше 4000 Гц (половина новой частоты),
 * «сворачивается» в низкие частоты — появляется характерный писк и металлический
 * призрак. Поэтому сначала режем низкочастотный фильтр, и только потом прореживаем.
 *
 * Фильтр — оконная sinc (Хэмминга), пересчёт коэффициентов на лету под нужную
 * частоту среза. Для синтеза речи точность более чем достаточна.
 */
public final class PcmConverter {

    private PcmConverter() {
    }

    /**
     * Понижает частоту дискретизации с фильтром нижних частот.
     *
     * @param src     исходные сэмплы
     * @param srcRate частота источника, Гц
     * @param dstRate частота назначения, Гц (должна быть меньше srcRate)
     * @return сэмплы на частоте dstRate
     */
    public static float[] downsample(float[] src, int srcRate, int dstRate) {
        if (src == null || src.length == 0 || srcRate == dstRate) {
            return src == null ? new float[0] : src;
        }
        if (dstRate > srcRate) {
            throw new IllegalArgumentException("Нужен повышатель частоты, а не понижатель: "
                    + srcRate + " -> " + dstRate);
        }

        // Частота среза чуть ниже Найквиста новой частоты, чтобы край не заворачивался
        double cutoffHz = dstRate * 0.45;
        float[] kernel = lowPassKernel(cutoffHz, srcRate, 32);

        float[] filtered = convolve(src, kernel);
        return decimate(filtered, srcRate, dstRate);
    }

    /** Прореживание с интерполяцией по дробному шагу. */
    private static float[] decimate(float[] src, int srcRate, int dstRate) {
        double ratio = (double) srcRate / dstRate;
        int outLen = (int) Math.floor(src.length / ratio);
        float[] out = new float[outLen];
        for (int i = 0; i < outLen; i++) {
            double pos = i * ratio;
            int idx = (int) pos;
            float frac = (float) (pos - idx);
            float a = src[idx];
            // последний отсчёт может выйти за границу — тогда просто берём его
            float b = (idx + 1 < src.length) ? src[idx + 1] : a;
            out[i] = a + (b - a) * frac;
        }
        return out;
    }

    /** Свёртка с ядром; края обрабатываются как нули. */
    private static float[] convolve(float[] src, float[] kernel) {
        int half = kernel.length / 2;
        float[] out = new float[src.length];
        for (int i = 0; i < src.length; i++) {
            float sum = 0f;
            for (int k = 0; k < kernel.length; k++) {
                int j = i + k - half;
                if (j >= 0 && j < src.length) {
                    sum += src[j] * kernel[k];
                }
            }
            out[i] = sum;
        }
        return out;
    }

    /**
     * Низкочастотный фильтр: sinc(cutoff) * окно Хэмминга, нормировка к единице.
     */
    static float[] lowPassKernel(double cutoffHz, int sampleRate, int taps) {
        int n = taps | 1;                      // нечётное число отсчётов
        double fc = cutoffHz / sampleRate;      // нормированная частота (0..0.5)
        double[] h = new double[n];
        int mid = n / 2;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            int k = i - mid;
            double sinc = (k == 0) ? 2 * fc : Math.sin(2 * Math.PI * fc * k) / (Math.PI * k);
            double w = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (n - 1));  // Хэмминг
            h[i] = sinc * w;
            sum += h[i];
        }
        float[] kernel = new float[n];
        for (int i = 0; i < n; i++) {
            kernel[i] = sum != 0 ? (float) (h[i] / sum) : 0f;
        }
        return kernel;
    }
}