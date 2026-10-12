package com.clinic.backend.user;

import com.clinic.backend.common.ForbiddenException;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Пользователи (раздел «Пользователи», только ADMIN):
 *   GET    /api/users       — список {id, login, firstName, lastName, role}
 *   DELETE /api/users/{id}  — удалить пользователя
 *
 * Пароль в ответах не отдаётся никогда: администратору не нужно видеть
 * пароли (ни открытые, ни захешированные) других пользователей.
 * Формат роли — канонический код (ADMIN / PATIENT), тот же, что в login/me/JWT;
 * человекочитаемую метку («администратор») рендерит клиент.
 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "Пользователи: список и удаление (только ADMIN)")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping
    public List<UserDto> all(Authentication authentication) {
        requireAdmin(authentication);
        return service.listAll();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> delete(
            @PathVariable("id") Long id,
            Authentication authentication) {
        requireAdmin(authentication);
        service.delete(id, authentication.getName());
        return ResponseEntity.ok(Map.of("message", "Пользователь удалён"));
    }

    private void requireAdmin(Authentication authentication) {
        boolean isAdmin = authentication.getAuthorities()
                .contains(new SimpleGrantedAuthority("ROLE_ADMIN"));
        if (!isAdmin) {
            throw new ForbiddenException("Доступно только администратору");
        }
    }
}