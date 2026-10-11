package com.clinic.backend.nlu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сверка Java-токенизатора с эталоном коллеги (kotlin_testvectors.json):
 * поле bpe_ids для 12 фраз. Если этот тест зелёный — ByteLevel BPE портирован
 * корректно, и расхождения фреймов относятся к самой ONNX-модели, а не к коду.
 */
class BpeTokenizerTest {

    private static final Path[] VECTOR_CANDIDATES = {
            Path.of("../ai_model/clinic_lm/models/kotlin_testvectors.json"),
            Path.of("../../ai_model/clinic_lm/models/kotlin_testvectors.json"),
            Path.of("../../models/kotlin_testvectors.json"),
    };

    @Test
    void matchesReferenceBpeIds() throws Exception {
        Path vectors = null;
        for (Path candidate : VECTOR_CANDIDATES) {
            if (Files.isReadable(candidate)) {
                vectors = candidate;
                break;
            }
        }
        if (vectors == null) {
            return; // эталонных векторов нет — пропускаем
        }
        // ВАЖНО: tokenizer.json берём из ТОЙ ЖЕ папки, что и вектора:
        // у разных чекпоинтов разный порядок vocab, и эталон сгенерирован
        // именно парным токенизатором.
        Path tokenizer = vectors.getParent().resolve("tokenizer.json");
        System.out.println("BpeTokenizerTest: вектора=" + vectors + ", токенизатор=" + tokenizer);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(Files.readAllBytes(vectors));
        BpeTokenizer tok = new BpeTokenizer(tokenizer);

        int checked = 0;
        for (JsonNode c : root.path("cases")) {
            String text = c.path("text").asText();
            List<Integer> expected = new ArrayList<>();
            for (JsonNode id : c.path("bpe_ids")) {
                expected.add(id.asInt());
            }
            assertEquals(expected, tok.encode(text), "bpe_ids для: " + text);
            checked++;
        }
        assertTrue(checked >= 10, "ожидалось не меньше 10 кейсов, проверено " + checked);
    }
}
