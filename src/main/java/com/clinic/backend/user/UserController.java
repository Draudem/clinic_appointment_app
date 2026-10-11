package com.clinic.backend.user;

import com.clinic.backend.common.ForbiddenException;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Список пользователей для раздела «Пользователи» фронтенда (только ADMIN).
 * Формат: {id, login, password, firstName, lastName, role}, где role —
 * канонический код роли (ADMIN / PATIENT) — та же форма, что в login/me/JWT.
 * Человекочитаемую метку («Администратор») при необходимости делает клиент.
 * Фронтенд сам рендерит пароли — это требование демо-фронтенда.
 * firstName/lastName добавлены как обязательные поля профиля пользователя.
 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "Список пользователей (только ADMIN)")
public class UserController {

    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
    }

    @GetMapping
    public List<Map<String, Object>> all(Authentication authentication) {
        boolean isAdmin = authentication.getAuthorities()
                .contains(new SimpleGrantedAuthority("ROLE_ADMIN"));
        if (!isAdmin) {
            throw new ForbiddenException("Доступно только администратору");
        }
        return users.findAll().stream()
                .map(u -> Map.<String, Object>of(
                        "id", u.getId(),
                        "login", u.getLogin(),
                        "password", u.getPassword(),
                        "firstName", u.getFirstName(),
                        "lastName", u.getLastName(),
                        "role", u.getRole().name()))
                .toList();
    }
}
