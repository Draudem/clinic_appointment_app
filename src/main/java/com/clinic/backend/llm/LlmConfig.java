package com.clinic.backend.llm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация LLM-этапа. Клиент создаётся всегда (чтобы отдавать статус),
 * но при app.llm.enabled=false любой вызов chat() кидает
 * LlmUnavailableException — остальные слои должны это обрабатывать.
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    @Bean
    public LlmClient llmClient(LlmProperties props) {
        return new OpenAiCompatibleLlmClient(props);
    }
}
