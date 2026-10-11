package com.clinic.backend.voice;

import com.clinic.backend.llm.LlmClient;
import com.clinic.backend.nlu.NluService;
import com.clinic.backend.rag.ClinicalKnowledgeBase;
import com.clinic.backend.rag.Retriever;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Выбор парсера команд. По умолчанию — nlu (локальная модель). Свойство
 * app.voice.parser переключает на rule или rag-llm без изменения остального кода.
 */
@Configuration
@EnableConfigurationProperties(VoiceProperties.class)
public class CommandParserConfig {

    @Bean
    @Primary
    public CommandParser commandParser(VoiceProperties props,
                                       NluService nlu,
                                       ClinicalKnowledgeBase knowledgeBase,
                                       Retriever retriever,
                                       LlmClient llm) {
        String mode = props.getParser() == null ? "nlu" : props.getParser().toLowerCase();
        return switch (mode) {
            case "rule" -> new RuleCommandParser(knowledgeBase);
            case "rag-llm", "rag", "llm" ->
                    new RagLlmCommandParser(retriever, llm, new RuleCommandParser(knowledgeBase));
            default -> new NluCommandParser(nlu, knowledgeBase);
        };
    }

    /** Отдельные бины движков — доступны для сравнения в тестах и отладке. */
    @Bean
    public RuleCommandParser ruleCommandParser(ClinicalKnowledgeBase knowledgeBase) {
        return new RuleCommandParser(knowledgeBase);
    }

    @Bean
    public NluCommandParser nluCommandParser(NluService nlu, ClinicalKnowledgeBase knowledgeBase) {
        return new NluCommandParser(nlu, knowledgeBase);
    }
}
