package com.clinic.backend.stt;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Синтез речи Piper (VITS) через sherpa-onnx — женский голос, русский.
 *
 * Модель: vits-piper-ru_RU-irina-medium-int8 (20 МБ в архиве). Тот же движок, что
 * и для распознавания, поэтому новых зависимостей не появляется.
 *
 * ПОЧЕМУ PIPER, А НЕ ДРУГИЕ
 * ------------------------
 *  - русский голос родной для модели (Piper обучен на русской речи), а не один из
 *    70+ языков мультиязычной модели;
 *  - в одной модели ОДИН голос: не нужно подбирать sid вслепую;
 *  - фонемы считает espeak-ng из комплекта модели, словарь-лексикон не нужен;
 *  - int8-версия весит 20 МБ и на процессоре быстрее полной — на телефоне разницы
 *    почти не слышно, потому что канал 8 кГц отрезает верха диапазона.
 *
 * Конструктор не бросает исключений: если модели нет, ready/synthesized просто
 * не работают, а причина попадает в статус — сервис продолжает принимать звонки.
 */
public class SherpaTtsEngine implements ReplySynthesizer {

    private static final Logger log = LoggerFactory.getLogger(SherpaTtsEngine.class);

    private final String modelDir;
    private final float speed;

    private OfflineTts tts;
    private boolean ready;
    private String initError;
    private int sampleRate;

    public SherpaTtsEngine(String modelDir, int numThreads, float speed) {
        this.modelDir = modelDir;
        this.speed = speed;
        try {
            init(numThreads);
            ready = true;
        } catch (Throwable e) {
            ready = false;
            initError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("TTS-модель не инициализирована ({}): {}",
                    e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private void init(int numThreads) throws Exception {
        Path dir = Paths.get(modelDir);
        Path model = dir.resolve("ru_RU-irina-medium.onnx");
        Path tokens = dir.resolve("tokens.txt");
        Path data = dir.resolve("espeak-ng-data");

        if (!Files.isReadable(model)) {
            throw new IllegalStateException("Файл модели синтеза не найден: " + model.toAbsolutePath());
        }
        if (!Files.isReadable(tokens)) {
            throw new IllegalStateException("Файл токенов синтеза не найден: " + tokens.toAbsolutePath());
        }
        if (!Files.isReadable(data)) {
            throw new IllegalStateException("Каталог espeak-ng-data не найден: " + data.toAbsolutePath());
        }

        tts = new OfflineTts(OfflineTtsConfig.builder()
                .setModel(OfflineTtsModelConfig.builder()
                        .setVits(OfflineTtsVitsModelConfig.builder()
                                .setModel(model.toAbsolutePath().toString())
                                .setTokens(tokens.toAbsolutePath().toString())
                                .setDataDir(data.toAbsolutePath().toString())
                                .build())
                        .setNumThreads(numThreads)
                        .setProvider("cpu")
                        .build())
                // Генерируем целиком: фраза короткая, а разбивка на предложения
                // только добавила бы задержки перед первым звуком.
                .setMaxNumSentences(1)
                .build());
        sampleRate = tts.getSampleRate();

        // Тёплый прогон — ловим проблемы JNI на старте, а не в первом звонке
        float[] warm = tts.generate("Проверка", 0, speed).getSamples();
        log.info("TTS-модель Piper загружена: {} (частота выхода {} Гц, тёплый прогон {} сэмплов)",
                model.getFileName(), sampleRate, warm.length);
    }

    @Override
    public synchronized float[] synthesize(String text) {
        if (!ready || text == null || text.isBlank()) {
            return new float[0];
        }
        try {
            // Piper: один голос в модели, sid всегда 0.
            GeneratedAudio audio = tts.generate(text, 0, speed);
            float[] samples = audio.getSamples();
            return samples == null ? new float[0] : samples;
        } catch (Throwable e) {
            log.warn("Ошибка синтеза речи: {}", e.getMessage());
            return new float[0];
        }
    }

    @Override
    public int sampleRate() {
        return sampleRate > 0 ? sampleRate : 22050;
    }

    @Override
    public boolean available() {
        return ready;
    }

    @Override
    public String name() {
        return ready ? "piper:ru_RU-irina" : "unavailable";
    }

    public String error() {
        return initError;
    }
}