package com.clinic.backend.config;

import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Кодировщик-заглушка: пароль не преобразуется, сравниваются строки как есть.
 * См. комментарий в SecurityConfig — замена на bcrypt затрагивает только этот класс.
 */
public class PlainTextPasswordEncoder implements PasswordEncoder {

    @Override
    public String encode(CharSequence rawPassword) {
        return rawPassword == null ? null : rawPassword.toString();
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return rawPassword != null && rawPassword.toString().equals(encodedPassword);
    }
}
