package com.clinic.backend.mis;

import com.clinic.backend.mis.dto.MisClientDto;
import com.clinic.backend.mis.dto.MisVisit;

import java.time.LocalDate;

/**
 * Порт интеграции с МИС «Инфоклиника» (тестовый стенд «Регистратура»).
 */
public interface MisClient {

    /** Доступна ли интеграция (есть токен, не отключена конфигом). */
    boolean available();

    /**
     * Ищет клиента по ФИО и дате рождения или создаёт нового.
     * (Стенд делает это одним эндпоинтом POST /clients).
     */
    MisClientDto findOrCreateClient(String lastName, String firstName, LocalDate birthDate);

    /** Создаёт запись. */
    MisVisit createVisit(Long clientId, Long doctorId, String scheduledAt);

    /** Отменяет запись (PATCH status="Отменена"). */
    MisVisit cancelVisit(Long visitId);
}