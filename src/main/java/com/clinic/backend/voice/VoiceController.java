package com.clinic.backend.voice;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Голосовой ассистент записи.
 *
 * Мобильное приложение распознаёт речь НА УСТРОЙСТВЕ и присылает текст:
 *   POST /api/voice/command  {"text":"запишите к стоматологу завтра утром"}
 *   POST /api/voice/answer   {"sessionId":1,"text":"да"}   (продолжение)
 * Если всё же прислали аудио (пока заглушка STT):
 *   POST /api/voice/audio    multipart/form-data, поле "audio"
 *
 * После предложения ассистент просит подтверждение (PENDING_CONFIRMATION):
 * ответ «да»/«нет» продолжает диалог, запись выполняется только при «да».
 */
@RestController
@RequestMapping("/api/voice")
@Tag(name = "Voice", description = "Голосовая запись (текст от мобильного STT)")
public class VoiceController {

    private final DialogueManager dialogue;
    private final SpeechToText speechToText;
    private final DialogueSessionRepository sessions;
    private final CommandParser parser;
    private final VoiceProperties props;

    public VoiceController(DialogueManager dialogue,
                           SpeechToText speechToText,
                           DialogueSessionRepository sessions,
                           CommandParser parser,
                           VoiceProperties props) {
        this.dialogue = dialogue;
        this.speechToText = speechToText;
        this.sessions = sessions;
        this.parser = parser;
        this.props = props;
    }

    public record VoiceTextRequest(Long sessionId, @NotBlank(message = "text обязателен") String text) {
    }

    @PostMapping("/command")
    @Operation(summary = "Начать диалог записи (текст)")
    public VoiceReply command(Authentication auth, @Valid @RequestBody VoiceTextRequest request) {
        return dialogue.handle(auth.getName(), request.sessionId(), request.text());
    }

    @PostMapping("/answer")
    @Operation(summary = "Продолжить диалог записи (уточнение или подтверждение)")
    public VoiceReply answer(Authentication auth, @Valid @RequestBody VoiceTextRequest request) {
        return dialogue.handle(auth.getName(), request.sessionId(), request.text());
    }

    @PostMapping(value = "/audio", consumes = "multipart/form-data")
    @Operation(summary = "Голосовая запись из аудио (STT пока заглушка)")
    public VoiceReply audio(Authentication auth,
                            @RequestParam(value = "sessionId", required = false) Long sessionId,
                            @RequestParam("audio") org.springframework.web.multipart.MultipartFile audio) {
        try {
            String text = speechToText.transcribe(audio.getBytes(), audio.getContentType());
            return dialogue.handle(auth.getName(), sessionId, text);
        } catch (java.io.IOException e) {
            throw new SpeechToTextException("Не удалось прочитать аудио: " + e.getMessage());
        }
    }

    @GetMapping("/parser")
    @Operation(summary = "Какой парсер команд активен")
    public Map<String, Object> parser() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("parser", parser.name());
        info.put("configured", props.getParser());
        info.put("speechToText", speechToText.name());
        info.put("maxExchanges", props.getMaxExchanges());
        return info;
    }

    @GetMapping("/sessions")
    @Operation(summary = "Мои голосовые сессии")
    public List<Map<String, Object>> mySessions(Authentication auth) {
        return sessions.findByPatientLoginOrderByIdDesc(auth.getName()).stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.getId());
                    m.put("stage", s.getStage());
                    m.put("specialty", s.getSpecialty());
                    m.put("exchanges", s.getExchanges());
                    m.put("handedOff", s.isHandedOff());
                    m.put("updatedAt", s.getUpdatedAt().toString());
                    return m;
                })
                .toList();
    }
}
