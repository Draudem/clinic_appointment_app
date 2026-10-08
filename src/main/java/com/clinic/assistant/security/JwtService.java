package com.clinic.assistant.security;

import com.clinic.assistant.user.Role;
import com.clinic.assistant.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Выпуск и проверка JWT (HS256).
 *
 * <p>Токен содержимое: {@code sub} = login, {@code uid} = id пользователя, {@code role} = роль.
 * Секрет и время жизни настраиваются в application.yml ({@code app.security.jwt.*}).</p>
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${app.security.jwt.secret}") String secret,
                      @Value("${app.security.jwt.ttl}") String ttlExpression) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.security.jwt.secret должен быть не короче 32 байт");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = parseDuration(ttlExpression);
    }

    /** Выпускает подписанный токен для пользователя. */
    public String issue(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getLogin())
                .claim("uid", user.getId())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** Разбирает и проверяет токен; кидает исключение, если токен невалидный/просроченный. */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** Извлекает id пользователя из токена (или null, если поля нет). */
    public Long userId(Claims claims) {
        Object uid = claims.get("uid");
        return uid instanceof Number n ? n.longValue() : null;
    }

    /** Поддерживает форматы: «30s», «15m», «24h», «7d» либо просто секунды. */
    static Duration parseDuration(String expression) {
        String value = expression.trim().toLowerCase();
        if (value.matches("\\d+")) {
            return Duration.ofSeconds(Long.parseLong(value));
        }
        long amount = Long.parseLong(value.substring(0, value.length() - 1));
        return switch (value.charAt(value.length() - 1)) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("Непонятный формат времени жизни токена: " + expression);
        };
    }
}
