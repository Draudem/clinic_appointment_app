package com.clinic.backend.nlu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ByteLevel BPE-токенизатор, читающий tokenizer.json модели коллеги (формат
 * HuggingFace tokenizers). Точный порт Kotlin-реализации из
 * ANDROID_INTEGRATION.md (там же описан алгоритм и его сверка с Python).
 */
public class BpeTokenizer {

    private final Map<String, Integer> vocab;
    private final Map<Pair, Integer> ranks;
    private final List<String> addedTokens;
    private final Map<String, Integer> addedTokenIds;
    private final Map<Integer, Character> byteToUni;
    private final int unkId;

    private static final Pattern SPLIT = Pattern.compile(
            "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+");

    private record Pair(String a, String b) {
    }

    public BpeTokenizer(Path tokenizerJson) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try {
            root = mapper.readTree(Files.readAllBytes(tokenizerJson));
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать токенизатор: " + tokenizerJson, e);
        }

        JsonNode vocabNode = root.path("model").path("vocab");
        vocab = new HashMap<>();
        for (var it = vocabNode.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            vocab.put(e.getKey(), e.getValue().asInt());
        }
        unkId = vocab.getOrDefault("<UNK>", 1);

        ranks = new HashMap<>();
        JsonNode mergesNode = root.path("model").path("merges");
        for (int i = 0; i < mergesNode.size(); i++) {
            JsonNode m = mergesNode.get(i);
            String a;
            String b;
            if (m.isArray() && m.size() >= 2) {
                a = m.get(0).asText();
                b = m.get(1).asText();
            } else {
                String[] pair = m.asText().split(" ");
                a = pair[0];
                b = pair.length > 1 ? pair[1] : "";
            }
            ranks.put(new Pair(a, b), i);
        }

        addedTokens = new ArrayList<>();
        addedTokenIds = new HashMap<>();
        JsonNode added = root.path("added_tokens");
        for (int i = 0; i < added.size(); i++) {
            JsonNode t = added.get(i);
            String content = t.path("content").asText();
            addedTokens.add(content);
            addedTokenIds.put(content, t.path("id").asInt());
        }
        addedTokens.sort((x, y) -> Integer.compare(y.length(), x.length()));

        byteToUni = bytesToUnicode();
    }

    /** GPT-2 bytes_to_unicode: печатаемые байты остаются, остальные -> U+0100+. */
    private static Map<Integer, Character> bytesToUnicode() {
        Map<Integer, Character> map = new HashMap<>();
        List<Integer> keep = new ArrayList<>();
        for (int b = 33; b <= 126; b++) {
            keep.add(b);
        }
        for (int b = 161; b <= 172; b++) {
            keep.add(b);
        }
        for (int b = 174; b <= 255; b++) {
            keep.add(b);
        }
        int n = 0;
        for (int b = 0; b < 256; b++) {
            if (keep.contains(b)) {
                map.put(b, (char) b);
            } else {
                map.put(b, (char) (256 + n++));
            }
        }
        return map;
    }

    public Map<String, Integer> vocab() {
        return vocab;
    }

    /** id токена либо -1, если его нет. */
    public int idOf(String token) {
        return vocab.getOrDefault(token, -1);
    }

    public boolean contains(String token) {
        return vocab.containsKey(token);
    }

    public List<Integer> encode(String text) {
        // 1) спецтокены (added_tokens) вырезаются первыми, самый длинный
        List<Integer> ids = new ArrayList<>();
        String rest = text;
        while (!rest.isEmpty()) {
            String hit = null;
            for (String t : addedTokens) {
                if (t.length() > 1 && rest.startsWith(t)) {
                    hit = t;
                    break;
                }
            }
            if (hit == null) {
                break;
            }
            ids.add(addedTokenIds.get(hit));
            rest = rest.substring(hit.length());
        }

        // 2) regex-сплит (по исходному тексту) + byte-level + BPE-слияния.
        // Важно: ByteLevel кодирует UTF-8 БАЙТЫ куска, а не UTF-16 код-юниты,
        // поэтому кириллица превращается в видимые «Ð·Ð°...» токены, как в
        // tokenizer.json (byteToUni ключует байты 0..255).
        var matcher = SPLIT.matcher(rest);
        while (matcher.find()) {
            String piece = matcher.group();
            byte[] u8 = piece.getBytes(StandardCharsets.UTF_8);
            List<String> syms = new ArrayList<>();
            for (byte b : u8) {
                syms.add(String.valueOf(byteToUni.get(b & 0xFF)));
            }
            while (syms.size() > 1) {
                Pair best = null;
                int bestRank = Integer.MAX_VALUE;
                for (int i = 0; i < syms.size() - 1; i++) {
                    Pair p = new Pair(syms.get(i), syms.get(i + 1));
                    Integer r = ranks.get(p);
                    if (r != null && r < bestRank) {
                        bestRank = r;
                        best = p;
                    }
                }
                if (best == null) {
                    break;
                }
                List<String> next = new ArrayList<>();
                int i = 0;
                while (i < syms.size()) {
                    if (i < syms.size() - 1
                            && syms.get(i).equals(best.a)
                            && syms.get(i + 1).equals(best.b)) {
                        next.add(best.a + best.b);
                        i += 2;
                    } else {
                        next.add(syms.get(i));
                        i += 1;
                    }
                }
                syms = next;
            }
            for (String s : syms) {
                ids.add(vocab.getOrDefault(s, unkId));
            }
        }
        return ids;
    }
}
