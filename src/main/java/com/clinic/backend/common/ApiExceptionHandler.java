package com.clinic.backend.common;

import com.clinic.backend.auth.BadCredentialsException;
import com.clinic.backend.auth.LoginAlreadyExistsException;
import com.clinic.backend.mis.MisBadRequestException;
import com.clinic.backend.mis.MisConflictException;
import com.clinic.backend.mis.MisRateLimitedException;
import com.clinic.backend.mis.MisUnavailableException;
import com.clinic.backend.voice.SpeechToTextException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Единый формат ошибок: {"message": "...", ...}.
 * Фронтенд и мобилка могут показывать message пользователю как есть.
 *
 * <p>Ошибки МИС теперь тоже обрабатываются централизованно: раньше
 * {@link MisUnavailableException} уходила на клиент как 500 без пояснений,
 * и было не видно, что «внешняя система недоступна» и «внутренняя ошибка» —
 * это разные сценарии для оператора.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(LoginAlreadyExistsException.class)
    public ResponseEntity<Map<String, String>> loginTaken(LoginAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(SpeechToTextException.class)
    public ResponseEntity<Map<String, String>> stt(SpeechToTextException ex) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, String>> badCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, String>> authError(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", "Требуется авторизация"));
    }

    // --- МИС ---

    @ExceptionHandler(MisBadRequestException.class)
    public ResponseEntity<Map<String, String>> misBadRequest(MisBadRequestException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(MisConflictException.class)
    public ResponseEntity<Map<String, String>> misConflict(MisConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
    }

    /**
     * 429 от МИС. Отдаём 503 (Service Unavailable) с {@code Retry-After} —
     * клиент (мобильное приложение) может подождать и повторить, а не считать,
     * что всё сломалось.
     */
    @ExceptionHandler(MisRateLimitedException.class)
    public ResponseEntity<Map<String, Object>> misRateLimited(MisRateLimitedException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", ex.getMessage());
        body.put("retryAfterSeconds", ex.retryAfterSeconds());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(ex.retryAfterSeconds()))
                .body(body);
    }

    /**
     * МИС недоступна (500, timeout, нет токена). 502 Bad Gateway —
     * честно: это не наша ошибка, а проблема зависимости.
     */
    @ExceptionHandler(MisUnavailableException.class)
    public ResponseEntity<Map<String, String>> misUnavailable(MisUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("message", "Внешняя система записи недоступна: " + ex.getMessage()));
    }

    // --- общие ---

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}