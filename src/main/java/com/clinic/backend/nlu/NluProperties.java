package com.clinic.backend.nlu;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Конфигурация NLU-этапа (app.nlu.*). Единый источник настроек: смена модели,
 * длины контекста и лимита шагов выполняется через env без правок кода.
 */
@ConfigurationProperties(prefix = "app.nlu")
public class NluProperties {

    private boolean enabled = true;
    private String modelPath = "../../models/model.onnx";
    private String tokenizerPath = "../../models/tokenizer.json";
    private int contextLength = 128;
    private int maxSteps = 24;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getModelPath() {
        return modelPath;
    }

    public void setModelPath(String modelPath) {
        this.modelPath = modelPath;
    }

    public String getTokenizerPath() {
        return tokenizerPath;
    }

    public void setTokenizerPath(String tokenizerPath) {
        this.tokenizerPath = tokenizerPath;
    }

    public int getContextLength() {
        return contextLength;
    }

    public void setContextLength(int contextLength) {
        this.contextLength = contextLength;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }
}
