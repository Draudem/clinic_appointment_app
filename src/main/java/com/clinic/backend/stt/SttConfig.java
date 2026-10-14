package com.clinic.backend.stt;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Включает свойства app.stt.*. Сами бины создаёт {@link AsteriskAudioSocketServer.Config},
 * чтобы порт и модель настраивались в одном месте.
 */
@Configuration
@EnableConfigurationProperties({SttProperties.class, TtsProperties.class})
public class SttConfig {
}