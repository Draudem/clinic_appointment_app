package com.clinic.backend.nlu;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;

/**
 * Выбор реализации NLU-этапа. Сейчас — локальная ONNX-модель (или заглушка,
 * если app.nlu.enabled=false / модель не загрузилась). Позже сюда добавится
 * LLM-реализация (RAG через Ollama) без изменений остального кода.
 */
@Configuration
@EnableConfigurationProperties(NluProperties.class)
public class NluConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "app.nlu", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OnnxNluModel onnxNluModel(NluProperties props) {
        return new OnnxNluModel(
                Paths.get(props.getModelPath()),
                Paths.get(props.getTokenizerPath()),
                props.getContextLength(),
                props.getMaxSteps());
    }

    @Bean
    @ConditionalOnMissingBean(NluModel.class)
    public NluModel disabledNluModel() {
        return new NluModel() {
            @Override
            public NluFrame parse(String text) {
                return NluFrame.unsupportedFrame();
            }

            @Override
            public String name() {
                return "disabled";
            }
        };
    }
}
