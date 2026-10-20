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
        String target = endpointPrefix;
        String callUuid = UUID.randomUUID().toString(); // Генерируем настоящий UUID

        // Запоминаем, что этот UUID принадлежит записи #appointmentId
        ACTIVE_REMINDER_CALLS.put(callUuid, appointmentId);

        // Передаем UUID в Asterisk как переменную
        String cmd = String.format(
                "asterisk -rx \"channel originate %s extension %s@%s\"",
                target, callUuid, context
        );

        log.info("С-10: Инициируем звонок по записи #{} с UUID {}", appointmentId, callUuid);

        try {
            Process process = Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", cmd});
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);

            if (!finished) {
                process.destroy();
                return false;
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("С-10: Asterisk успешно принял команду Originate");
                return true;
            } else {
                ACTIVE_REMINDER_CALLS.remove(callUuid); // Очищаем при ошибке
                return false;
            }
        } catch (Exception e) {
            ACTIVE_REMINDER_CALLS.remove(callUuid);
            log.error("С-10: Ошибка Asterisk: {}", e.getMessage());
            return false;
        }
    }
}