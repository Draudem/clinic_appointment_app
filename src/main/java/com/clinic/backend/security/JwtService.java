package com.clinic.backend.security;

import com.clinic.backend.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

/**
 * Выдача и проверка JWT.
 * Секрет и время жизни берутся из application.yml (env JWT_SECRET / JWT_TTL_SECONDS)
 * — это же позволяет поднимать приложение в Docker без правки кода.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.ttl-seconds}") long ttlSeconds) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("app.jwt.secret должен быть не короче 32 символов");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(String login, Role role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(login)
                .claim("role", role.name())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlSeconds * 1000))
                .signWith(key)
                .compact();
    }

    /**
     * @return данные пользователя из токена либо empty, если токен отсутствует,
     * просрочен или подделан.
     */
    public Optional<JwtUser> verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Role role;
            try {
                role = Role.valueOf(claims.get("role", String.class));
            } catch (IllegalArgumentException | NullPointerException e) {
                return Optional.empty();
            }
            return Optional.of(new JwtUser(claims.getSubject(), role));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record JwtUser(String login, Role role) {
    }
}
