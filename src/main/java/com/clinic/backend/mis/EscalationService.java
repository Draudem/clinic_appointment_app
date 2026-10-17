package com.clinic.backend.mis;

import com.clinic.backend.mis.dto.MisClientDto;
import com.clinic.backend.mis.dto.MisEscalation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class EscalationService {
    private static final Logger log = LoggerFactory.getLogger(EscalationService.class);
    private final MisClient misClient;

    public EscalationService(MisClient misClient) {
        this.misClient = misClient;
    }

    public void escalate(String lastName, String firstName, LocalDate birthDate, String description, String ruleCode) {
        if (!misClient.available()) return;
        try {
            MisClientDto client = misClient.findOrCreateClient(lastName, firstName, birthDate);
            misClient.createEscalation(client.id(), "[" + ruleCode + "] " + description);
            log.info("Эскалация {} успешно зафиксирована в МИС", ruleCode);
        } catch (Exception e) {
            log.warn("Не удалось зафиксировать эскалацию в МИС: {}", e.getMessage());
        }
    }
}