package com.clinic.backend.schedule;

import com.clinic.backend.doctor.DoctorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * Выбор источника расписания.
 *
 * Бин {@link ScheduleProvider} всегда ровно один — переключение делается
 * значением {@code app.schedule.source}, а не правкой кода. Опечатка в значении
 * не должна приводить к «расписания нет вообще»: здесь она превращается в
 * понятное сообщение при старте.
 *
 * Новый источник подключается добавлением ветки в {@link #scheduleProvider} —
 * остальной код об этом не знает.
 */
@Configuration
@EnableConfigurationProperties(ScheduleProperties.class)
public class ScheduleConfig {

    private static final Logger log = LoggerFactory.getLogger(ScheduleConfig.class);

    @Bean
    public ScheduleProvider scheduleProvider(ScheduleProperties props, DoctorRepository doctors) {
        String source = props.getSource() == null ? "db" : props.getSource().trim().toLowerCase(Locale.ROOT);
        ScheduleProvider provider = switch (source) {
            case "db", "" -> new DbScheduleProvider(doctors);
            case "excel", "xlsx" -> new ExcelScheduleProvider(doctors, props);
            default -> throw new IllegalStateException(
                    "Неизвестный источник расписания app.schedule.source=" + props.getSource()
                            + ". Допустимо: db | excel");
        };
        ScheduleProvider.Health health = provider.health();
        if (health.readable()) {
            log.info("Источник расписания: {} ({})", provider.name(),
                    health.entries() > 0 ? "строки читаются" : "пока пусто");
        } else {
            // Не роняем старт: сценарий должен уметь сказать «перевожу на оператора»,
            // а не падать. Проблема видна в /api/voice/parser и в логах.
            log.error("Источник расписания {} недоступен: {}", provider.name(), health.error());
        }
        return provider;
    }
}