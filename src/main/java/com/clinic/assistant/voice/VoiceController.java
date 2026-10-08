package com.clinic.assistant.voice;

import com.clinic.assistant.security.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Голосовой интерфейс для мобильного приложения.
 *
 * <pre>
 * POST /api/voice/command    multipart: audio → полный конвейер
 *                            аудио → [STT] → текст → [парсер] → фрейм → слоты из БД
 * POST /api/voice/transcribe multipart: audio → только распознавание речи
 * POST /api/voice/parse      {text} → только разбор текста (отладка без аудио)
 * GET  /api/voice/requests   → журнал запросов (админ)
 * </pre>
 */
@RestController
@RequestMapping("/api/voice")
@Tag(name = "Голосовой ассистент")
public class VoiceController {

    private final VoicePipelineService pipeline;
    private final VoiceRequestRepository voiceRequestRepository;

    public VoiceController(VoicePipelineService pipeline, VoiceRequestRepository voiceRequestRepository) {
        this.pipeline = pipeline;
        this.voiceRequestRepository = voiceRequestRepository;
    }

    @PostMapping("/command")
    @Operation(summary = "Полный конвейер: аудио → текст → фрейм → варианты записи")
    public VoiceCommandResponse command(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam("audio") MultipartFile audio) {
        return pipeline.process(audio, principal);
    }

    @PostMapping("/transcribe")
    @Operation(summary = "Только распознавание речи (этап 1)")
    public VoicePipelineService.TranscribeResponse transcribe(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam("audio") MultipartFile audio) {
        return pipeline.transcribe(audio, principal);
    }

    @PostMapping("/parse")
    @Operation(summary = "Только разбор текста в фрейм (этапы 2–3), без аудио")
    public VoiceCommandResponse parse(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody VoicePipelineService.ParseRequest request) {
        return pipeline.processText(request.text(), principal);
    }

    @GetMapping("/requests")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Журнал голосовых запросов (админ)")
    public List<VoiceRequestView> journal() {
        return voiceRequestRepository.findTop50ByOrderByCreatedAtDesc().stream()
                .map(r -> new VoiceRequestView(
                        r.getId(),
                        r.getCreatedAt().toString(),
                        r.getTranscript(),
                        r.getIntent(),
                        r.getStatus().name(),
                        r.getError(),
                        r.getAudioPath()))
                .toList();
    }

    /** Строка журнала. */
    public record VoiceRequestView(Long id,
                                   String createdAt,
                                   String transcript,
                                   String intent,
                                   String status,
                                   String error,
                                   String audioPath) {
    }
}
