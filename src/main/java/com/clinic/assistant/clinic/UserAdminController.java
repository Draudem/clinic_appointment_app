package com.clinic.assistant.clinic;

import com.clinic.assistant.security.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Пользователи системы (только администратор) — вкладка «Пользователи».
 *
 * <p>Пароль отдаётся замаскированным: фронтенд рисует колонку, но реальный
 * пароль из API не утекает.</p>
 */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Пользователи (админ)")
public class UserAdminController {

    private final com.clinic.assistant.user.UserRepository userRepository;

    public UserAdminController(com.clinic.assistant.user.UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public record UserDto(Long id, String login, String password, String role, String roleCode, String fullName) {
    }

    @GetMapping
    @Operation(summary = "Все пользователи (пароль маскируется)")
    public List<UserDto> all(@AuthenticationPrincipal AuthPrincipal principal) {
        return userRepository.findAll().stream()
                .map(u -> new UserDto(
                        u.getId(),
                        u.getLogin(),
                        "••••••",
                        u.getRole().getTitle(),
                        u.getRole().name(),
                        u.getFullName()))
                .toList();
    }
}
