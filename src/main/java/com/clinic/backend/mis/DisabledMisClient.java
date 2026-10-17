package com.clinic.backend.mis;

import com.clinic.backend.mis.dto.MisClientDto;
import com.clinic.backend.mis.dto.MisEscalation;
import com.clinic.backend.mis.dto.MisVisit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;

/** Заглушка: используется, если MIS_TOKEN не задан. */
public class DisabledMisClient implements MisClient {

    private static final Logger log = LoggerFactory.getLogger(DisabledMisClient.class);

    public DisabledMisClient() {
        log.warn("МИС отключена (нет MIS_TOKEN). Запросы к стенду будут возвращать ошибку перевода на оператора.");
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public MisClientDto findOrCreateClient(String lastName, String firstName, LocalDate birthDate) {
        throw new MisUnavailableException("МИС недоступна: не настроен токен.");
    }

    @Override
    public MisVisit createVisit(Long clientId, Long doctorId, String scheduledAt) {
        throw new MisUnavailableException("МИС недоступна: не настроен токен.");
    }

    @Override
    public MisVisit cancelVisit(Long visitId) {
        throw new MisUnavailableException("МИС недоступна: не настроен токен.");
    }

    // --- ДОБАВЛЕНО: реализация для эскалации ---
    @Override
    public MisEscalation createEscalation(Long clientId, String description) {
        throw new MisUnavailableException("МИС недоступна: не настроен токен.");
    }
}