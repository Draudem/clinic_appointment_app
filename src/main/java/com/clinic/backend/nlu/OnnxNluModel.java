package com.clinic.backend.nlu;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Локальный ONNX-инференс NLU-модели коллеги (clinic_lm): текст → semantic frame.
 * Конвейер: ByteLevel BPE (BpeTokenizer) → decoder-only Transformer (ONNX) с
 * constrained decoding по автомату (FrameAutomaton) — точный порт
 * train/constrained.py и ANDROID_INTEGRATION.md.
 *
 * Если модель/токенизатор недоступны, конструктор не падает: parse() вернёт
 * <UNSUPPORTED>, а статус отобразится в /api/nlu/status. Это позволяет держать
 * NLU-этап заменяемым (заглушка/LLM) без остановки всего бэкенда.
 */
public class OnnxNluModel implements NluModel {

    private static final Logger log = LoggerFactory.getLogger(OnnxNluModel.class);

    private final Path modelPath;
    private final Path tokenizerPath;
    private final int contextLength;
    private final int maxSteps;

    private OrtEnvironment env;
    private OrtSession session;
    private String inputName;
    private BpeTokenizer tokenizer;
    private Map<Integer, String> idToToken;
    private int padId;
    private int bosId;
    private int eosId;
    private int userId;
    private int assistantId;
    private int vocabSize;
    private boolean ready;
    private String initError;

    public OnnxNluModel(Path modelPath, Path tokenizerPath, int contextLength, int maxSteps) {
        this.modelPath = modelPath;
        this.tokenizerPath = tokenizerPath;
        this.contextLength = contextLength;
        this.maxSteps = maxSteps;
        try {
            init();
            ready = true;
        } catch (Exception e) {
            ready = false;
            initError = e.getMessage();
            log.error("NLU-модель не инициализирована ({}): {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private void init() throws Exception {
        if (!Files.isReadable(modelPath)) {
            throw new IllegalStateException("Файл модели не найден: " + modelPath.toAbsolutePath());
        }
        if (!Files.isReadable(tokenizerPath)) {
            throw new IllegalStateException("Файл токенизатора не найден: " + tokenizerPath.toAbsolutePath());
        }
        tokenizer = new BpeTokenizer(tokenizerPath);
        vocabSize = tokenizer.vocab().size();
        idToToken = new HashMap<>();
        for (Map.Entry<String, Integer> e : tokenizer.vocab().entrySet()) {
            idToToken.putIfAbsent(e.getValue(), e.getKey());
        }
        padId = requireId("<PAD>");
        bosId = requireId("<BOS>");
        eosId = requireId("<EOS>");
        userId = requireId("<USER>");
        assistantId = requireId("<ASSISTANT>");

        env = OrtEnvironment.getEnvironment();
        this.session = env.createSession(modelPath.toString(), new OrtSession.SessionOptions());
        Set<String> names = session.getInputNames();
        inputName = names.iterator().next();

        // Проверяем, что токенизатор соответствует модели. Модель и словарь связаны
        // номерами токенов: если взять новый ONNX со старым токенизатором, бэкенд
        // будет молча кодировать и декодировать не те токены — исключения не будет,
        // будет просто «модель стала глупее». Поэтому отсутствие интентов ловим здесь.
        List<String> absentIntents = FrameVocab.INTENTS.stream()
                .filter(t -> tokenizer.idOf(t) < 0)
                .toList();
        if (absentIntents.isEmpty()) {
            log.info("Словарь интентов совпадает с моделью ({})", FrameVocab.INTENTS.size());
        } else {
            log.error("В токенизаторе нет интентов {} — он не соответствует модели. "
                            + "Проверьте NLU_TOKENIZER_PATH: для этой модели нужен tokenizer_v2, "
                            + "старый tokenizer.json переставляет номера токенов.",
                    absentIntents);
        }

        // Сверяем словарь времени с моделью. Если модель умеет выдавать
        // токен, которого нет в FrameVocab, он будет замаскирован при декодировании
        // и модель подставит другое значение (например, другое время). Ловим это
        // на старте, а не по жалобам пациентов.
        java.util.List<String> unsupported = FrameVocab.unsupportedTimeTokens(tokenizer.vocab().keySet());
        if (unsupported.isEmpty()) {
            log.info("Словарь времени совпадает с моделью ({} токенов)", FrameVocab.TIME_OF_DAY.size());
        } else {
            log.warn("Модель умеет {} токенов времени, которых нет в FrameVocab — "
                    + "они будут замаскированы, время может разъезжаться: {}",
                    unsupported.size(), unsupported);
        }

        log.info("NLU-модель загружена: {} (вход '{}', vocab {}, ctx {})",
                modelPath.getFileName(), inputName, vocabSize, contextLength);

        // тёплый прогон — ловим ошибки несовместимости сразу, а не в первом запросе
        parse("проверка");
        log.info("NLU-модель: тёплый прогон выполнен");
    }

    private int requireId(String token) {
        int id = tokenizer.idOf(token);
        if (id < 0) {
            throw new IllegalStateException("В токенизаторе нет спецтокена " + token);
        }
        return id;
    }

    @Override
    public String name() {
        return ready ? "onnx:" + modelPath.getFileName() : "onnx:unavailable";
    }

    public boolean isReady() {
        return ready;
    }

    public String initError() {
        return initError;
    }

    @Override
    public synchronized NluFrame parse(String text) {
        if (!ready) {
            return NluFrame.unsupportedFrame();
        }
        if (text == null || text.isBlank()) {
            return NluFrame.unsupportedFrame();
        }
        List<Integer> ids = tokenizer.encode(text);
        List<Integer> seq = new ArrayList<>();
        seq.add(bosId);
        seq.add(userId);
        seq.addAll(ids);
        seq.add(assistantId);

        FrameAutomaton automaton = new FrameAutomaton(tokenizer.vocab());
        List<String> frameTokens = new ArrayList<>();
        long[] in = new long[contextLength];
        try {
            for (int step = 0; step < maxSteps; step++) {
                int len = Math.min(seq.size(), contextLength);
                int start = seq.size() - len;
                Arrays.fill(in, padId);
                for (int i = 0; i < len; i++) {
                    in[i] = seq.get(start + i);
                }
                Set<Integer> allowed = automaton.allowed();
                if (allowed.isEmpty()) {
                    break;
                }
                int best = argmax(runModel(in), len - 1, allowed);
                if (best < 0 || best == eosId) {
                    break;
                }
                frameTokens.add(idToToken.getOrDefault(best, "<UNK>"));
                seq.add(best);
                automaton.step(best);
                if (automaton.isDone()) {
                    break;
                }
            }
        } catch (OrtException e) {
            log.error("Ошибка инференса NLU-модели", e);
            return NluFrame.unsupportedFrame();
        }
        return NluFrame.parse(String.join(" ", frameTokens));
    }

    /** Прогон модели, возвращает плоский буфер логитов [1,ctx,vocab]. */
    private FloatBuffer runModel(long[] in) throws OrtException {
        long[][] batch = new long[][]{in};
        try (OnnxTensor input = OnnxTensor.createTensor(env, batch)) {
            Map<String, OnnxTensor> feeds = new HashMap<>();
            feeds.put(inputName, input);
            try (OrtSession.Result result = session.run(feeds)) {
                OnnxTensor out = (OnnxTensor) result.get(0);
                return out.getFloatBuffer();
            }
        }
    }

    private int argmax(FloatBuffer logits, int position, Set<Integer> allowed) {
        int base = position * vocabSize;
        int best = -1;
        float bestVal = Float.NEGATIVE_INFINITY;
        for (int id : allowed) {
            float v = logits.get(base + id);
            if (v > bestVal) {
                bestVal = v;
                best = id;
            }
        }
        return best;
    }

    public void close() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (OrtException e) {
            log.warn("Ошибка закрытия ONNX-сессии", e);
        }
    }
}
