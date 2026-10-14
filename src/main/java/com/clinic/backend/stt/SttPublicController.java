package com.clinic.backend.stt;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ОТЛАДОЧНЫЙ доступ к распознанным фразам — без авторизации, только чтение.
 *
 * Зачем: при наладке телефонии (Asterisk AudioSocket) неудобно каждый раз
 * получать JWT. Напарник подключается к /api/stt/public/last без токена
 * и сразу видит результат.
 *
 * ЧТО ЭТО НЕ ДЕЛАЕТ:
 *   - не отдаёт ничего, кроме текста распознавания (нет данных пациентов, врачей, записей);
 *   - ничего не меняет (нет POST/PUT/DELETE).
 *
 * БЕЗОПАСНОСТЬ: здесь лежат транскрипты разговоров с пациентами. Это осознанная
 * уступка на этапе стенда. Перед выкладкой наружу — поставить обратно
 * .authenticated() в SecurityConfig (одна строка).
 *
 * Выключается флагом: app.stt.public-read=false
 */
@RestController
@RequestMapping("/api/stt/public")
@Tag(name = "STT (отладка)", description = "Распознанные фразы без авторизации — только для наладки")
public class SttPublicController {

    private final TranscriptStore transcripts;
    private final SpeechRecognizer recognizer;
    private final ReplySynthesizer synthesizer;
    private final AsteriskAudioSocketServer server;
    private final boolean enabled;

    public SttPublicController(TranscriptStore transcripts,
                               SpeechRecognizer recognizer,
                               ReplySynthesizer synthesizer,
                               AsteriskAudioSocketServer server,
                               @Value("${app.stt.public-read:true}") boolean enabled) {
        this.transcripts = transcripts;
        this.recognizer = recognizer;
        this.synthesizer = synthesizer;
        this.server = server;
        this.enabled = enabled;
    }

    @GetMapping("/last")
    @Operation(summary = "Последняя распознанная фраза (без авторизации, для отладки)")
    public Map<String, Object> last() {
        if (!enabled) {
            return Map.of("found", false, "message", "Отладочный доступ выключен (app.stt.public-read=false)");
        }
        TranscriptStore.Utterance u = transcripts.last();
        if (u == null) {
            return Map.of("found", false, "text", "");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("found", true);
        m.put("callId", u.callId());
        m.put("text", u.text());
        m.put("seconds", u.seconds());
        m.put("at", u.at().toString());
        return m;
    }

    @GetMapping("/recent")
    @Operation(summary = "Последние фразы по всем звонкам (без авторизации)")
    public Map<String, Object> recent() {
        if (!enabled) {
            return Map.of("count", 0, "utterances", List.of(),
                    "message", "Отладочный доступ выключен");
        }
        List<Map<String, Object>> items = transcripts.recent().stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("callId", u.callId());
            m.put("text", u.text());
            m.put("seconds", u.seconds());
            m.put("at", u.at().toString());
            return m;
        }).toList();
        return Map.of("count", items.size(), "utterances", items);
    }

    @GetMapping("/status")
    @Operation(summary = "Готов ли сервер принять звонок (без авторизации)")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("modelReady", recognizer.ready());
        m.put("listening", server.isListening());
        m.put("tts", synthesizer.name());
        m.put("ttsReady", synthesizer.available());
        m.put("ttsRate", synthesizer.sampleRate());
        return m;
    }
}