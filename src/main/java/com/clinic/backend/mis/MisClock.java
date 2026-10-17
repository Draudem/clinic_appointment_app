package com.clinic.backend.mis;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Единые часы для всего приложения.
 * Стенд МИС работает в часовом поясе GMT+4, поэтому все даты, которые мы
 * отправляем и проверяем, должны формироваться в этом поясе.
 */
@Component
public class MisClock {

    private final ZoneId zone = ZoneOffset.ofHours(4);

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    public LocalDateTime now() {
        return LocalDateTime.now(zone);
    }
}