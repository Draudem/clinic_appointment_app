package com.clinic.assistant.security;

import com.clinic.assistant.user.Role;

/**
 * Пользователь, прошедший аутентификацию (principal в контексте Spring Security).
 */
public record AuthPrincipal(Long id, String login, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
