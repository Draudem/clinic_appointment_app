package com.clinic.backend.auth;

import com.clinic.backend.auth.dto.AuthResponse;
import com.clinic.backend.auth.dto.LoginRequest;
import com.clinic.backend.auth.dto.RegisterRequest;

/**
 * Контракт авторизации. Сейчас — JpaAuthService (PostgreSQL),
 * можно подменить на другой провайдер (LDAP, внешний IdP) без правок контроллеров.
 */
public interface AuthService {

    /** Регистрация нового пользователя. @throws LoginAlreadyExistsException */
    AuthResponse register(RegisterRequest request);

    /** Логин: проверка кредов и выдача JWT. @throws BadCredentialsException */
    AuthResponse login(LoginRequest request);

    /** Текущий аутентифицированный пользователь по логину из JWT. */
    AuthResponse me(String login);
}
