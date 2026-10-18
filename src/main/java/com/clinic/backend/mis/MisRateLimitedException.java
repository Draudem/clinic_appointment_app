package com.clinic.backend.mis;

/**
 * Стенд МИС ответил 429 Too Many Requests.
 *
 * <p>Отдельный тип, а не {@link MisUnavailableException}, потому что политика
 * разная: на 429 мы <b>ждём и повторяем</b> (стенд сам говорит сколько —
 * заголовок {@code Retry-After}), а на 500/timeout — сразу переводим оператору.
 * Слить их в один тип значило бы либо повторять бессмысленно, либо не повторять
 * там, где это безопасно.
 *
 * <p>{@link #retryAfterSeconds()} — из заголовка {@code Retry-After}.
 * Если заголовка нет (стенд не прислал или формат не распознан), возвращается
 * {@link #DEFAULT_RETRY_AFTER_SECONDS} — консервативное значение из документации
 * стенда («10 запросов в минуту» → 6 секунд между запросами).
 */
public class MisRateLimitedException extends RuntimeException {

    /** Значение по умолчанию, если стенд не прислал Retry-After. */
    public static final long DEFAULT_RETRY_AFTER_SECONDS = 6L;

    private final long retryAfterSeconds;

    public MisRateLimitedException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}