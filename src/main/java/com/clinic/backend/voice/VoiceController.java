package com.clinic.backend.voice;

import com.clinic.backend.mis.MisClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/voice")
@Tag(name = "Voice", description = "Голосовая запись (текст от мобильного STT)")
public class VoiceController {

    private final DialogueManager dialogue;
    private final DialogueSessionRepository sessions;
    private final CommandParser parser;
    private final VoiceProperties props;
    private final MisClient misClient;

    public VoiceController(DialogueManager dialogue,
                           DialogueSessionRepository sessions,
                           CommandParser parser,
                           VoiceProperties props,
                           MisClient misClient) {
        this.dialogue = dialogue;
        this.sessions = sessions;
        this.parser = parser;
        this.props = props;
        this.misClient = misClient;
    }

    public record VoiceTextRequest(Long sessionId,
                                   @NotBlank(message = "text обязателен") String text) {
    }

    @PostMapping("/command")
    @Operation(summary = "Начать диалог записи (текст)")
    public VoiceReply command(Authentication auth, @Valid @RequestBody VoiceTextRequest request) {
        return dialogue.handleForApi(auth.getName(), request.sessionId(), request.text());
    }

    @PostMapping("/answer")
    @Operation(summary = "Продолжить диалог записи (уточнение или подтверждение)")
    public VoiceReply answer(Authentication auth, @Valid @RequestBody VoiceTextRequest request) {
        return dialogue.handleForApi(auth.getName(), request.sessionId(), request.text());
    }

    @GetMapping("/parser")
    @Operation(summary = "Какой парсер команд активен и доступна ли МИС")
    public Map<String, Object> parser() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("parser", parser.name());
        info.put("configured", props.getParser());
        info.put("maxExchanges", props.getMaxExchanges());
        info.put("misAvailable", misClient.available());
        info.put("misClient", misClient.getClass().getSimpleName());
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