package com.clinic.backend.config;

import com.clinic.backend.user.Role;
import com.clinic.backend.user.User;
import com.clinic.backend.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Начальные данные: администратор для демо-доступа фронтенда (admin/admin123).
 * Идемпотентно — при перезапуске не дублируется.
 */
@Configuration
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    @Bean
    ApplicationRunner seedAdmin(UserRepository users, PasswordEncoder encoder) {
        return args -> {
            if (!users.existsByLogin("admin")) {
                // Демо-администратор: имя/фамилия теперь обязательные поля
                users.save(new User("admin", encoder.encode("admin123"), Role.ADMIN,
                        "Иван", "Админов"));
                log.info("Создан администратор admin/admin123 (Иван Админов)");
            }
        };
    }
}
