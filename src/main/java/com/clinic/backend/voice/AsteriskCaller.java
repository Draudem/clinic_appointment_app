package com.clinic.backend.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class AsteriskCaller {

    private static final Logger log = LoggerFactory.getLogger(AsteriskCaller.class);

    @Value("${app.asterisk.endpoint-prefix:PJSIP/phone-092@gateway}")
    private String endpointPrefix;

    @Value("${app.asterisk.context:reminder-answered}")
    private String context;

    // Кэш: UUID -> ID записи
    public static final Map<String, Long> ACTIVE_REMINDER_CALLS = new ConcurrentHashMap<>();

    public boolean originateCall(Long appointmentId) {
        String target = endpointPrefix; // Берется из конфига, не из текста пациента
        String callUuid = UUID.randomUUID().toString();

        ACTIVE_REMINDER_CALLS.put(callUuid, appointmentId);

        // Безопасный вызов через ProcessBuilder (без шелла)
        ProcessBuilder pb = new ProcessBuilder(
                "asterisk", "-rx",
                String.format("channel originate %s extension %s@%s", target, callUuid, context)
        );

        log.info("С-10: Инициируем звонок по записи #{} с UUID {}", appointmentId, callUuid);

        try {
            Process process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);

            if (!finished) {
                process.destroy();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            log.error("С-10: Ошибка Asterisk: {}", e.getMessage());
            ACTIVE_REMINDER_CALLS.remove(callUuid);
            return false;
        }
    }
}