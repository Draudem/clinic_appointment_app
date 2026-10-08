package com.clinic.assistant.auth;

import com.clinic.assistant.security.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Эндпоинты авторизации (публичные).
 *
 * <pre>
 * POST /api/auth/register  {login, password, fullName?, role?} → 201 {token, ...}
 * POST /api/auth/login     {login, password}                   → 200 {token, ...}
 * GET  /api/auth/me       (Authorization: Bearer …)            → 200 {login, role, ...}
 * </pre>
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Авторизация")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Регистрация пациента (или администратора, если разрешено конфигом)")
    public AuthService.AuthResponse register(@Valid @RequestBody AuthService.RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    @Operation(summary = "Вход, возвращает JWT")
    public AuthService.AuthResponse login(@Valid @RequestBody AuthService.LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    @Operation(summary = "Текущий пользователь по токену")
    public AuthService.AuthResponse me(@AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) {
            throw new com.clinic.assistant.common.UnauthorizedException("Требуется авторизация");
        }
        return authService.me(principal.id());
    }
}
