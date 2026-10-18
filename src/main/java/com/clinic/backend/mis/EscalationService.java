package com.clinic.backend.mis;

import com.clinic.backend.mis.dto.MisClientDto;
import com.clinic.backend.mis.dto.MisEscalation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Фиксирует факт перевода в МИС (заявка типа «Требует внимания оператора»).
 * Логи не содержат ФИО пациента целиком (152-ФЗ) — только маскированное.
 */
@Service
public class EscalationService {

    private static final Logger log = LoggerFactory.getLogger(EscalationService.class);
    private final MisClient misClient;

    public EscalationService(MisClient misClient) {
        this.misClient = misClient;
    }

    public void escalate(String lastName, String firstName, LocalDate birthDate,
                         String description, String ruleCode) {
        if (!misClient.available()) return;
        try {
            MisClientDto client = misClient.findOrCreateClient(lastName, firstName, birthDate);
            // В description могут быть персональные данные (жалоба, симптомы).
            // МИС получает текст целиком, но в лог пишем только код правила и длину.
            misClient.createEscalation(client.id(), "[" + ruleCode + "] " + description);
            log.info("Эскалация {} зафиксирована в МИС (client_id={}, {} симв. описания)",
                    ruleCode, client.id(), description == null ? 0 : description.length());
        } catch (Exception e) {
            log.warn("Не удалось зафиксировать эскалацию {}: {}", ruleCode, e.getMessage());
        }
    }
}