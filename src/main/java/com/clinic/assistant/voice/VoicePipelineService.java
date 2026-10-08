package com.clinic.assistant.voice;

import com.clinic.assistant.ai.IntentParserClient;
import com.clinic.assistant.ai.SpeechToTextClient;
import com.clinic.assistant.ai.SpeechToTextClient.AudioContent;
import com.clinic.assistant.ai.frame.Intent;
import com.clinic.assistant.ai.frame.VoiceFrame;
import com.clinic.assistant.clinic.Appointment;
import com.clinic.assistant.clinic.AppointmentRepository;
import com.clinic.assistant.clinic.AppointmentStatus;
import com.clinic.assistant.clinic.ScheduleService;
import com.clinic.assistant.clinic.ScheduleService.SlotOccurrence;
import com.clinic.assistant.clinic.Specialty;
import com.clinic.assistant.clinic.SpecialtyRepository;
import com.clinic.assistant.common.AiUnavailableException;
import com.clinic.assistant.common.BadRequestException;
import com.clinic.assistant.config.VoiceProperties;
import com.clinic.assistant.security.AuthPrincipal;
import com.clinic.assistant.user.UserRepository;
import com.clinic.assistant.voice.VoiceCommandResponse.Candidate;
import com.clinic.assistant.voice.VoiceCommandResponse.Resolved;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * ГОЛОСОКОНВЕЙЕР.
 *
 * <pre>
 *  аудио (мобильное приложение)
 *      │
 *      ▼
 *  [1] SpeechToTextClient   аудио → текст        (stub | http → модель коллеги)
 *      │
 *      ▼
 *  [2] IntentParserClient   текст → VoiceFrame   (stub | http → ONNX-модель из models/)
 *      │
 *      ▼
 *  [3] TimeWindowResolver + SpecialtyResolver + ScheduleService
 *                          фрейм → конкретные свободные слоты в БД
 *      │
 *      ▼
 *  VoiceCommandResponse     ← мобильному приложению / фронтенду
 * </pre>
 *
 * <p>Каждый этап заменяется независимо: шаги 1–2 переключаются в
 * application.yml, шаг 3 — чистый Java-код над БД.</p>
 */
@Service
public class VoicePipelineService {

    private static final Logger log = LoggerFactory.getLogger(VoicePipelineService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final SpeechToTextClient speechToTextClient;
    private final IntentParserClient intentParserClient;
    private final ScheduleService scheduleService;
    private final SpecialtyRepository specialtyRepository;
    private final SpecialtyResolver specialtyResolver;
    private final TimeWindowResolver timeWindowResolver;
    private final AppointmentRepository appointmentRepository;
    private final VoiceRequestRepository voiceRequestRepository;
    private final UserRepository userRepository;
    private final VoiceProperties properties;
    private final ObjectMapper objectMapper;

    public VoicePipelineService(SpeechToTextClient speechToTextClient,
                                IntentParserClient intentParserClient,
                                ScheduleService scheduleService,
                                SpecialtyRepository specialtyRepository,
                                SpecialtyResolver specialtyResolver,
                                TimeWindowResolver timeWindowResolver,
                                AppointmentRepository appointmentRepository,
                                VoiceRequestRepository voiceRequestRepository,
                                UserRepository userRepository,
                                VoiceProperties properties,
                                ObjectMapper objectMapper) {
        this.speechToTextClient = speechToTextClient;
        this.intentParserClient = intentParserClient;
        this.scheduleService = scheduleService;
        this.specialtyRepository = specialtyRepository;
        this.specialtyResolver = specialtyResolver;
        this.timeWindowResolver = timeWindowResolver;
        this.appointmentRepository = appointmentRepository;
        this.voiceRequestRepository = voiceRequestRepository;
        this.userRepository = userRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // Публичные операции
    // ------------------------------------------------------------------

    /** Этап 1 отдельно: аудио → текст (полезно для отладки и для «двухшагового» UX). */
    public TranscribeResponse transcribe(MultipartFile file, AuthPrincipal principal) {
        VoiceRequest entry = newEntry(principal);
        try {
            AudioContent audio = toAudioContent(file);
            entry.setContentType(audio.contentType());
            entry.setAudioPath(saveToDisk(audio));
            SpeechToTextClient.SpeechToTextResult result = speechToTextClient.transcribe(audio);
            entry.setTranscript(result.text());
            entry.setStatus(VoiceRequest.Status.OK);
            saveQuietly(entry);
            return new TranscribeResponse(entry.getId(), result.text(), result.model());
        } catch (AiUnavailableException e) {
            fail(entry, e);
            throw e;
        } catch (IOException e) {
            fail(entry, e);
            throw new BadRequestException("Не удалось сохранить аудиофайл: " + e.getMessage());
        }
    }

    /** Полный конвейер: аудио → текст → фрейм → варианты записи. */
    @Transactional
    public VoiceCommandResponse process(MultipartFile file, AuthPrincipal principal) {
        VoiceRequest entry = newEntry(principal);
        String text;
        try {
            AudioContent audio = toAudioContent(file);
            entry.setContentType(audio.contentType());
            entry.setAudioPath(saveToDisk(audio));
            text = speechToTextClient.transcribe(audio).text();
            entry.setTranscript(text);
        } catch (AiUnavailableException e) {
            fail(entry, e);
            throw e;
        } catch (IOException e) {
            fail(entry, e);
            throw new BadRequestException("Не удалось сохранить аудиофайл: " + e.getMessage());
        }
        return parseAndMatch(text, principal, entry);
    }

    /** Этапы 2–3 от текста: удобно, когда STT уже сделан на устройстве или в отладке. */
    public VoiceCommandResponse processText(String text, AuthPrincipal principal) {
        VoiceRequest entry = newEntry(principal);
        entry.setTranscript(text);
        return parseAndMatch(text, principal, entry);
    }

    // ------------------------------------------------------------------
    // Этапы конвейера
    // ------------------------------------------------------------------

    private VoiceCommandResponse parseAndMatch(String text, AuthPrincipal principal, VoiceRequest entry) {
        VoiceFrame frame;
        try {
            frame = intentParserClient.parse(text);
        } catch (AiUnavailableException e) {
            fail(entry, e);
            throw e;
        }
        entry.setFrameJson(toJson(frame));
        entry.setIntent(frame.intent().name());

        VoiceCommandResponse response = match(text, frame, principal);
        entry.setStatus(VoiceRequest.Status.OK);
        saveQuietly(entry);
        return new VoiceCommandResponse(
                entry.getId(),
                response.text(),
                response.frame(),
                response.resolved(),
                response.candidates(),
                response.question(),
                response.message(),
                response.availableSpecialties());
    }

    /** Шаг 3: фрейм модели → конкретные варианты из БД. */
    private VoiceCommandResponse match(String text, VoiceFrame frame, AuthPrincipal principal) {
        LocalDate today = LocalDate.now();

        if (frame.intent() == Intent.UNSUPPORTED) {
            return new VoiceCommandResponse(null, text, frame, null, List.of(),
                    "Не совсем понял запрос. Попробуйте так: «Записаться к терапевту завтра в десять утра».",
                    "Запрос не распознан",
                    specialtyNames());
        }

        TimeWindow window = timeWindowResolver.resolve(frame.timeFactors(), today);
        Specialty specialty = specialtyResolver.resolve(frame, text);
        Resolved resolved = new Resolved(
                specialty != null ? specialty.getName() : null,
                window.from().format(DATE),
                window.to().format(DATE),
                window.earliest().format(TIME),
                window.latest().format(TIME));

        // --- Отмена / перенос: показываем свои записи в этом окне ---
        if (frame.intent() == Intent.CANCEL || frame.intent() == Intent.RESCHEDULE) {
            List<Candidate> own = ownAppointments(principal, window);
            String question = own.isEmpty()
                    ? "Не нашёл ваших записей в указанном периоде. Назовите врача или дату."
                    : (frame.needsClarification() ? "Уточните, какую именно запись отменить или перенести." : null);
            String verb = frame.intent() == Intent.CANCEL ? "отмены" : "переноса";
            log.debug("Найдено {} записей для {}", own.size(), verb);
            return new VoiceCommandResponse(null, text, frame, resolved, own, question,
                    own.isEmpty() ? "Записей не найдено" : "Найдено записей: " + own.size(),
                    null);
        }

        // --- Запись ---
        if (specialty == null) {
            return new VoiceCommandResponse(null, text, frame, resolved, List.of(),
                    "К какой специальности записаться? Доступны: " + String.join(", ", specialtyNames()) + ".",
                    "Специальность не распознана",
                    specialtyNames());
        }

        LocalDateTime now = LocalDateTime.now();
        List<Candidate> candidates = findCandidates(window, specialty, now);
        boolean nearby = false;

        // Точное время («в 10:00») не совпало ни с одним окном —
        // показываем ближайшие слоты в пределах ±2 часов.
        if (candidates.isEmpty() && window.earliest().equals(window.latest())) {
            TimeWindow broad = new TimeWindow(
                    window.from(),
                    window.to(),
                    window.earliest().minusHours(2).isBefore(LocalTime.of(0, 0))
                            ? LocalTime.of(0, 0) : window.earliest().minusHours(2),
                    window.latest().plusHours(2).isAfter(LocalTime.of(23, 59))
                            ? LocalTime.of(23, 59) : window.latest().plusHours(2));
            candidates = findCandidates(broad, specialty, now);
            nearby = !candidates.isEmpty();
        }

        if (candidates.isEmpty()) {
            return new VoiceCommandResponse(null, text, frame, resolved, List.of(),
                    "Свободных окон у специальности «" + specialty.getName() + "» с "
                            + window.from().format(DATE) + " по " + window.to().format(DATE)
                            + " не нашлось. Попробуйте указать другое время.",
                    "Свободных окон нет",
                    null);
        }

        String question = frame.needsClarification()
                ? "Получилось понять запрос не полностью — проверьте, пожалуйста, выбранные варианты."
                : null;
        String message = nearby
                ? "Точного времени нет — показал ближайшие окна рядом с ним: " + candidates.size()
                : "Найдено вариантов: " + candidates.size();
        return new VoiceCommandResponse(null, text, frame, resolved, candidates, question, message, null);
    }

    /** Свободные слоты специальности в окне. */
    private List<Candidate> findCandidates(TimeWindow window, Specialty specialty, LocalDateTime now) {
        return scheduleService.occurrences(window.from(), window.to()).stream()
                .filter(o -> !o.booked())
                .filter(o -> o.dateTime().isAfter(now))
                .filter(o -> window.matches(o.date(), o.time()))
                .filter(o -> o.specialty().equals(specialty.getName()))
                .limit(properties.getMaxCandidates())
                .map(o -> new Candidate(null, o.doctorId(), o.fio(), o.specialty(),
                        o.date().format(DATE), o.time().format(TIME), o.slotId(), true))
                .toList();
    }

    private List<Candidate> ownAppointments(AuthPrincipal principal, TimeWindow window) {
        if (principal == null) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        return appointmentRepository
                .findByPatientIdAndStatusOrderByDateAscStartTimeAsc(principal.id(), AppointmentStatus.BOOKED)
                .stream()
                .filter(a -> !a.getDate().isBefore(now.toLocalDate()))
                .filter(a -> window.matches(a.getDate(), a.getStartTime()))
                .limit(properties.getMaxCandidates())
                .map(a -> new Candidate(
                        a.getId(),
                        a.getDoctor().getId(),
                        a.getDoctor().getFio(),
                        a.getDoctor().getSpecialty().getName(),
                        a.getDate().format(DATE),
                        a.getStartTime().format(TIME),
                        null,
                        true))
                .toList();
    }

    // ------------------------------------------------------------------
    // Утилиты
    // ------------------------------------------------------------------

    private VoiceRequest newEntry(AuthPrincipal principal) {
        var user = principal == null ? null : userRepository.findById(principal.id()).orElse(null);
        return new VoiceRequest(user);
    }

    private AudioContent toAudioContent(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Приложите аудиофайл полем «audio»");
        }
        return AudioContent.of(file.getBytes(), file.getContentType(), file.getOriginalFilename());
    }

    /** Сохраняет аудио в {@code app.voice.storage-dir}, возвращает путь. */
    private String saveToDisk(AudioContent audio) throws IOException {
        Path dir = Path.of(properties.getStorageDir());
        Files.createDirectories(dir);
        String filename = UUID.randomUUID() + "." + extension(audio);
        Path target = dir.resolve(filename);
        Files.write(target, audio.data());
        return target.toString();
    }

    private static String extension(AudioContent audio) {
        String name = audio.filename().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            return name.substring(dot + 1);
        }
        return switch (audio.contentType()) {
            case "audio/webm" -> "webm";
            case "audio/wav", "audio/x-wav" -> "wav";
            case "audio/ogg" -> "ogg";
            case "audio/mpeg" -> "mp3";
            case "audio/mp4", "audio/x-m4a" -> "m4a";
            case "audio/amr" -> "amr";
            default -> "bin";
        };
    }

    private List<String> specialtyNames() {
        List<String> names = new ArrayList<>();
        specialtyRepository.findAll().forEach(s -> names.add(s.getName()));
        return names;
    }

    private String toJson(VoiceFrame frame) {
        try {
            return objectMapper.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            return frame.raw();
        }
    }

    private void fail(VoiceRequest entry, Exception e) {
        entry.setStatus(VoiceRequest.Status.AI_ERROR);
        entry.setError(truncate(e.getMessage(), 512));
        saveQuietly(entry);
    }

    private void saveQuietly(VoiceRequest entry) {
        try {
            voiceRequestRepository.save(entry);
        } catch (Exception e) {
            log.warn("Не удалось сохранить журнал голосового запроса: {}", e.getMessage());
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    // ------------------------------------------------------------------
    // DTO
    // ------------------------------------------------------------------

    /** Ответ этапа STT. */
    public record TranscribeResponse(Long requestId, String text, String model) {
    }

    /** Вход этапа парсера (POST /api/voice/parse). */
    public record ParseRequest(@jakarta.validation.constraints.NotBlank(message = "Укажите текст") String text) {
    }
}
