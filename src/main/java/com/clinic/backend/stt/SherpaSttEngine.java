package com.clinic.backend.stt;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Локальное распознавание речи GigaAM через sherpa-onnx (NeMo CTC, русский).
 *
 * Модель та же, что у коллеги в мобильном приложении
 * (sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19), поэтому голос, который
 * распознаётся на устройстве, распознаётся и на сервере.
 *
 * Конструктор не бросает исключений: если JNI или файлы модели недоступны,
 * ready() вернёт false, а текст ошибки попадёт в /api/stt/status. Так STT
 * остаётся заменяемой заглушкой и не роняет весь бэкенд.
 */
public class SherpaSttEngine implements SpeechRecognizer {

    private static final Logger log = LoggerFactory.getLogger(SherpaSttEngine.class);

    /** Модель обучена строго на 16 кГц — это требование, а не настройка. */
    private static final int SAMPLE_RATE = 16000;
    private static final int FEATURE_DIM = 80;

    private final String modelDir;
    private final int numThreads;

    private OfflineRecognizer recognizer;
    private boolean ready;
    private String initError;

    public SherpaSttEngine(String modelDir, int numThreads) {
        this.modelDir = modelDir;
        this.numThreads = numThreads;
        try {
            init();
            ready = true;
        } catch (Throwable e) {
            // UnsatisfiedLinkError (нет JNI под эту ОС) — тоже Throwable, а не Exception
            ready = false;
            initError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("STT-модель не инициализирована ({}): {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private void init() throws Exception {
        Path dir = Paths.get(modelDir);
        Path model = dir.resolve("model.int8.onnx");
        Path tokens = dir.resolve("tokens.txt");

        if (!Files.isReadable(model)) {
            throw new IllegalStateException("Файл модели не найден: " + model.toAbsolutePath());
        }
        if (!Files.isReadable(tokens)) {
            throw new IllegalStateException("Файл токенизатора не найден: " + tokens.toAbsolutePath());
        }

        recognizer = new OfflineRecognizer(
                OfflineRecognizerConfig.builder()
                        .setFeatureConfig(FeatureConfig.builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setFeatureDim(FEATURE_DIM)
                                .build())
                        .setOfflineModelConfig(OfflineModelConfig.builder()
                                .setNemo(OfflineNemoEncDecCtcModelConfig.builder()
                                        .setModel(model.toAbsolutePath().toString())
                                        .build())
                                .setTokens(tokens.toAbsolutePath().toString())
                                .setNumThreads(numThreads)
                                .setProvider("cpu")
                                .build())
                        .build());

        // Тёплый прогон: ловим несовместимость JNI/модели на старте, а не в первом звонке
        float[] ping = new float[SAMPLE_RATE / 10];
        transcribeRaw(ping);
        log.info("STT-модель GigaAM загружена: {} (потоков: {})", model.getFileName(), numThreads);
    }

    @Override
    public String transcribe(float[] samples16k) {
        if (!ready || samples16k == null || samples16k.length == 0) {
            return "";
        }
        try {
            return transcribeRaw(samples16k);
        } catch (Throwable e) {
            log.warn("Ошибка распознавания: {}", e.getMessage());
            return "";
        }
    }

    private synchronized String transcribeRaw(float[] samples16k) {
        OfflineStream stream = recognizer.createStream();
        try {
            // ВАЖНО: второй аргумент — ЧАСТОТА ДИСКРЕТИЗАЦИИ, а не число отсчётов.
            // Проверено экспериментально на этой версии биндинга: если передать
            // samples.length, sherpa создаёт ресемплер «частота=180640 Гц -> 16000 Гц»,
            // и модель молча возвращает пустую строку. С SAMPLE_RATE работает корректно.
            stream.acceptWaveform(samples16k, SAMPLE_RATE);
            recognizer.decode(stream);
            String text = recognizer.getResult(stream).getText();
            return normalize(text);
        } finally {
            // Без release() нативная память течёт на каждом звонке
            stream.release();
        }
    }

    /** GigaAM иногда печатает лишние пробелы и мусорные токены — чистим. */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("[\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        return cleaned.toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean ready() {
        return ready;
    }

    @Override
    public String error() {
        return initError;
    }

    @Override
    public String name() {
        return ready ? "gigaam:sherpa-onnx" : "unavailable";
    }
}