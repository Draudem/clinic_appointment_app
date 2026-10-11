package com.clinic.backend.nlu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Эталонная проверка ВСЕГО конвейера (токенизатор + ONNX + ограниченное
 * декодирование) на 12 фразах из kotlin_testvectors.json.
 *
 * Отдельно печатает результат для разных пар (model.onnx, tokenizer.json):
 * у каждого чекпоинта СВОЙ токенизатор (разный порядок vocab), поэтому
 * смешивать пары нельзя. Тест фиксирует правильную пару и позволяет быстро
 * увидеть деградацию, если подменить один из файлов.
 */
class NluReferenceTest {

    private static final Path VECTORS = Path.of("../ai_model/clinic_lm/models/kotlin_testvectors.json");
    private static final Path GOOD_MODEL = Path.of("../ai_model/clinic_lm/models/model.onnx");
    private static final Path GOOD_TOKENIZER = Path.of("../ai_model/clinic_lm/models/tokenizer.json");
    private static final Path HACK_MODEL = Path.of("../../models/model.onnx");
    private static final Path HACK_TOKENIZER = Path.of("../../models/tokenizer.json");

    @Test
    void referencePairMatchesAllCases() throws Exception {
        if (!Files.isReadable(VECTORS) || !Files.isReadable(GOOD_MODEL) || !Files.isReadable(GOOD_TOKENIZER)) {
            return; // окружение без эталонов — пропускаем
        }
        JsonNode cases = new ObjectMapper().readTree(Files.readAllBytes(VECTORS)).path("cases");

        System.out.println("=== пара ai_model (model.onnx + tokenizer.json) ===");
        run(GOOD_MODEL, GOOD_TOKENIZER, cases, true);

        if (Files.isReadable(HACK_MODEL) && Files.isReadable(HACK_TOKENIZER)) {
            System.out.println("=== пара hackathon/models ===");
            run(HACK_MODEL, HACK_TOKENIZER, cases, false);
        }
    }

    private void run(Path model, Path tokenizer, JsonNode cases, boolean strict) {
        OnnxNluModel nlu = new OnnxNluModel(model, tokenizer, 128, 24);
        try {
            int ok = 0;
            List<String> diffs = new ArrayList<>();
            for (JsonNode c : cases) {
                String expected = c.path("expected_frame").asText();
                String got = nlu.parse(c.path("text").asText()).frame();
                if (expected.equals(got)) {
                    ok++;
                } else {
                    diffs.add("  «" + c.path("text").asText() + "»\n    expected: " + expected + "\n    got:      " + got);
                }
            }
            System.out.println("  совпало " + ok + "/" + cases.size());
            diffs.forEach(System.out::println);
            if (strict) {
                assertEquals(cases.size(), ok, "эталонная пара должна совпадать полностью");
            }
        } finally {
            nlu.close();
        }
    }
}
