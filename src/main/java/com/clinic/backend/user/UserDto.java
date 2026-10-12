package com.clinic.backend.user;

/**
 * DTO пользователя для админского списка /api/users.
 * Пароля здесь нет намеренно: администратору не нужно и не должно видеть
 * пароль (или его хеш) других пользователей — ни в этом, ни в каком-либо
 * другом ответе API.
 */
public record UserDto(
        Long id,
        String login,
        String firstName,
        String lastName,
        String role) {

    public static UserDto from(User u) {
        return new UserDto(u.getId(), u.getLogin(), u.getFirstName(), u.getLastName(),
                u.getRole().name());
    }
}