package com.clinic.assistant;

import com.clinic.assistant.config.AiProperties;
import com.clinic.assistant.config.VoiceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Голосовой ассистент клиники — точка входа.
 *
 * <p>Архитектура (по слоям):</p>
 * <ul>
 *   <li>{@code web} / controller — REST-эндпоинты для фронтенда и мобильного приложения;</li>
 *   <li>{@code service} — бизнес-логика (расписание, записи, сотрудники);</li>
 *   <li>{@code voice} — конвейер обработки голосового запроса;</li>
 *   <li>{@code ai} — порты (интерфейсы) доступа к ИИ-моделям + их реализации
 *       (заглушка / HTTP-клиент к развёрнутой модели).</li>
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties({AiProperties.class, VoiceProperties.class})
public class ClinicAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClinicAssistantApplication.class, args);
    }
}
