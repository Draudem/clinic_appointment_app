package com.clinic.assistant.config;

import com.clinic.assistant.ai.IntentParserClient;
import com.clinic.assistant.ai.SpeechToTextClient;
import com.clinic.assistant.ai.http.HttpIntentParserClient;
import com.clinic.assistant.ai.http.HttpSpeechToTextClient;
import com.clinic.assistant.ai.stub.StubIntentParserClient;
import com.clinic.assistant.ai.stub.StubSpeechToTextClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Подключение реализаций ИИ-этапов.
 *
 * <p>Здесь единственное место, которое нужно трогать при добавлении новой
 * реализации: написать класс с нужным интерфейсом и добавить пару
 * {@code @Bean + @ConditionalOnProperty}. Весь остальной конвейер (контроллеры,
 * сервисы, БД) от реализации не зависит.</p>
 *
 * <pre>
 * app.ai.stt.provider:    stub | http   → распознавание речи
 * app.ai.parser.provider: stub | http   → разбор текста в фрейм
 * </pre>
 */
@Configuration
public class AiClientsConfig {

    // ---------- Этап 1: речь → текст ----------

    @Bean
    @ConditionalOnProperty(name = "app.ai.stt.provider", havingValue = "stub", matchIfMissing = true)
    public SpeechToTextClient stubSpeechToTextClient(AiProperties properties) {
        return new StubSpeechToTextClient(properties.getStt().getStubText());
    }

    @Bean
    @ConditionalOnProperty(name = "app.ai.stt.provider", havingValue = "http")
    public SpeechToTextClient httpSpeechToTextClient(AiProperties properties) {
        return new HttpSpeechToTextClient(properties.getStt());
    }

    // ---------- Этап 2: текст → фрейм ----------

    @Bean
    @ConditionalOnProperty(name = "app.ai.parser.provider", havingValue = "stub", matchIfMissing = true)
    public IntentParserClient stubIntentParserClient() {
        return new StubIntentParserClient();
    }

    @Bean
    @ConditionalOnProperty(name = "app.ai.parser.provider", havingValue = "http")
    public IntentParserClient httpIntentParserClient(AiProperties properties) {
        return new HttpIntentParserClient(properties.getParser());
    }
}
