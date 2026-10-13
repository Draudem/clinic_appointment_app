package com.clinic.backend.stt;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Диагностика серверного STT и доступ к распознанным фразам.
 *
 *   GET /api/stt/status -> модель загружена, порт слушает, какой синтезатор
 *   GET /api/stt/last   -> последняя распознанная фраза
 *   GET /api/stt/recent -> последние фразы по всем звонкам
 *
 * Нужны для первого теста: напарник звонит, затем забирает текст отсюда.
 * Сам AudioSocket работает по TCP на отдельном порту и HTTP-эндпоинтов не требует.
 */
@RestController
@RequestMapping("/api/stt")
@Tag(name = "STT", description = "Серверное распознавание речи (Asterisk AudioSocket -> GigaAM)")
public class SttController {

    private final SpeechRecognizer recognizer;
    private final ReplySynthesizer synthesizer;
    private final TranscriptStore transcripts;
    private final AsteriskAudioSocketServer server;
    private final SttProperties props;

    public SttController(SpeechRecognizer recognizer,
                         ReplySynthesizer synthesizer,
                         TranscriptStore transcripts,
                         AsteriskAudioSocketServer server,
                         SttProperties props) {
        this.recognizer = recognizer;
        this.synthesizer = synthesizer;
        this.transcripts = transcripts;
        this.server = server;
        this.props = props;
    }

    @GetMapping("/status")
    @Operation(summary = "Состояние STT и порта AudioSocket")
    public Map<String, Object> status() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("enabled", props.isEnabled());
        info.put("model", recognizer.name());
        info.put("modelReady", recognizer.ready());
        info.put("modelError", recognizer.error());
        info.put("modelDir", props.getModelDir());
        info.put("audiosocketPort", props.getAudiosocketPort());
        info.put("listening", server.isListening());
        info.put("synthesizer", synthesizer.name());
        info.put("speakReplies", props.isSpeakReplies());
        info.put("silenceSeconds", props.getSilenceSeconds());
        return info;
    }

    @GetMapping("/last")
    @Operation(summary = "Последняя распознанная фраза")
    public Map<String, Object> last() {
        TranscriptStore.Utterance u = transcripts.last();
        if (u == null) {
            return Map.of("text", "", "found", false);
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
    @Operation(summary = "Последние распознанные фразы по всем звонкам")
    public Map<String, Object> recent() {
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
}