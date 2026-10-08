package com.clinic.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки обоих этапов ИИ-конвейера.
 *
 * <p>Этап 1 — распознавание речи (STT): аудио → текст.
 * Этап 2 — разбор текста в структурированный фрейм (интент / специальность / время / симптомы).</p>
 *
 * <p>Для каждого этапа выбирается реализация ({@code provider}):
 * <ul>
 *   <li>{@code stub} — заглушка, ничего внешнего не нужно (удобно для разработки и демо);</li>
 *   <li>{@code http} — HTTP-запрос к развёрнутой модели коллеги ({@code url}).</li>
 * </ul>
 * Переключение — только в application.yml / переменных окружения, код не меняется.</p>
 */
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /** Этап 1: аудио → текст. */
    private Stage stt = new Stage();

    /** Этап 2: текст → структурированный фрейм намерения. */
    private Stage parser = new Stage();

    public Stage getStt() {
        return stt;
    }

    public void setStt(Stage stt) {
        this.stt = stt;
    }

    public Stage getParser() {
        return parser;
    }

    public void setParser(Stage parser) {
        this.parser = parser;
    }

    public enum Provider {
        STUB, HTTP
    }

    public static class Stage {
        /** Какую реализацию использовать: stub | http. */
        private Provider provider = Provider.STUB;

        /** URL внешнего сервиса (для provider=http). */
        private String url;

        private int connectTimeoutMs = 3000;

        private int readTimeoutMs = 30000;

        /** Текст, который вернёт заглушка этапа STT. */
        private String stubText;

        public Provider getProvider() {
            return provider;
        }

        public void setProvider(Provider provider) {
            this.provider = provider;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        public String getStubText() {
            return stubText;
        }

        public void setStubText(String stubText) {
            this.stubText = stubText;
        }
    }
}
