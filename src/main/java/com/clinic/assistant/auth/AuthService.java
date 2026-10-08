package com.clinic.assistant.auth;

import com.clinic.assistant.common.ConflictException;
import com.clinic.assistant.common.UnauthorizedException;
import com.clinic.assistant.security.JwtService;
import com.clinic.assistant.user.Role;
import com.clinic.assistant.user.User;
import com.clinic.assistant.user.UserRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Регистрация и вход.
 *
 * <p>Пароли хранятся только в виде BCrypt-хэша, наружу пароль никогда не уходит.
 * После входа выдаётся JWT, который клиент передаёт в заголовке
 * {@code Authorization: Bearer &lt;token&gt;}.</p>
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final boolean allowAdminRegistration;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       @Value("${app.security.allow-admin-registration:false}") boolean allowAdminRegistration) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.allowAdminRegistration = allowAdminRegistration;
    }

    /** Заявка на регистрацию. */
    public record RegisterRequest(
            @NotBlank(message = "Укажите логин")
            @Size(min = 3, max = 64, message = "Логин: 3–64 символа")
            String login,

            @NotBlank(message = "Укажите пароль")
            @Size(min = 6, max = 72, message = "Пароль: минимум 6 символов")
            String password,

            String fullName,

            /** PATIENT (по умолчанию) или ADMIN — если разрешено конфигом. */
            String role) {
    }

    /** Заявка на вход. */
    public record LoginRequest(
            @NotBlank(message = "Укажите логин") String login,
            @NotBlank(message = "Укажите пароль") String password) {
    }

    /** Ответ клиенту: токен + данные пользователя. */
    public record AuthResponse(Long id, String token, String login, String role, String roleCode, String fullName) {
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        Role role = parseRole(request.role());
        if (role == Role.ADMIN && !allowAdminRegistration) {
            throw new com.clinic.assistant.common.BadRequestException(
                    "Регистрация администраторов отключена на сервере");
        }
        String login = request.login().trim();
        if (userRepository.existsByLogin(login)) {
            throw new ConflictException("Пользователь с логином «" + login + "» уже существует");
        }
        User user = new User(login,
                passwordEncoder.encode(request.password()),
                role,
                trimToNull(request.fullName()));
        userRepository.save(user);
        return toResponse(user, jwtService.issue(user));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByLogin(request.login().trim()).orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Неверный логин или пароль");
        }
        return toResponse(user, jwtService.issue(user));
    }

    @Transactional(readOnly = true)
    public AuthResponse me(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Пользователь не найден"));
        return toResponse(user, null);
    }

    private AuthResponse toResponse(User user, String token) {
        return new AuthResponse(
                user.getId(),
                token,
                user.getLogin(),
                user.getRole().getTitle(),
                user.getRole().name(),
                user.getFullName());
    }

    /** Принимает и коды (PATIENT/ADMIN), и русские названия. */
    private Role parseRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return Role.PATIENT;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "ADMIN", "АДМИНИСТРАТОР" -> Role.ADMIN;
            case "PATIENT", "ПАЦИЕНТ", "ПОЛЬЗОВАТЕЛЬ" -> Role.PATIENT;
            default -> throw new com.clinic.assistant.common.BadRequestException(
                    "Неизвестная роль: " + raw + " (допустимы PATIENT и ADMIN)");
        };
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
