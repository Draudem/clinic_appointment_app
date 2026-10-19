package com.clinic.backend.mis;

import com.clinic.backend.mis.dto.MisClientDto;
import com.clinic.backend.mis.dto.MisEscalation;
import com.clinic.backend.mis.dto.MisVisit;

import java.time.LocalDate;

/**
 * Заглушка MisClient для тестов и локальной разработки без сети.
 *
 * <p>В проде не используется: {@code MisConfig} создаёт либо {@link RestMisClient}
 * (при заданном {@code MIS_TOKEN}), либо {@link DisabledMisClient}.
 *
 * <p>С 2026-10-19 {@link MisClientDto} расширен полями {@code middle_name},
 * {@code comment}, {@code created_at} — стенд их возвращает, и без них
 * Jackson падал на UnrecognizedPropertyException. Здесь просто передаём null,
 * потому что заглушке эти поля не нужны.
 */
public class FakeMisClient implements MisClient {

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public MisClientDto findOrCreateClient(String lastName, String firstName, LocalDate birthDate) {
        return new MisClientDto(
                999L,
                lastName,
                firstName,
                null,                                    // middle_name
                birthDate.toString(),                    // birth_date
                null,                                    // comment
                "2026-10-17 12:00"                       // created_at
        );
    }

    @Override
    public MisVisit createVisit(Long clientId, Long doctorId, String scheduledAt) {
        return new MisVisit(
                888L,
                clientId,
                new MisVisit.MisDoctor(doctorId, "Тестовый Врач", "Терапевт", true),
                scheduledAt,
                "Запланирована",
                "2026-10-17 12:00"
        );
    }

    @Override
    public MisVisit cancelVisit(Long visitId) {
        return createVisit(999L, 1L, "2026-10-18 10:00");
    }

    @Override
    public MisEscalation createEscalation(Long clientId, String description) {
        return new MisEscalation(777L, clientId, description, "2026-10-17 12:00");
    }
}