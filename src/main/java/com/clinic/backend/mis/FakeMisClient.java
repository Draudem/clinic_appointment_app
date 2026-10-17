package com.clinic.backend.mis;

import com.clinic.backend.mis.dto.MisClientDto;
import com.clinic.backend.mis.dto.MisEscalation;
import com.clinic.backend.mis.dto.MisVisit;

import java.time.LocalDate;

public class FakeMisClient implements MisClient {
    @Override public boolean available() { return true; }
    @Override public MisClientDto findOrCreateClient(String lastName, String firstName, LocalDate birthDate) {
        return new MisClientDto(999L, lastName, firstName, birthDate.toString());
    }
    @Override public MisVisit createVisit(Long clientId, Long doctorId, String scheduledAt) {
        return new MisVisit(888L, clientId, new MisVisit.MisDoctor(doctorId, "Тестовый Врач", "Терапевт", true), scheduledAt, "Запланирована", "2026-10-17");
    }
    @Override public MisVisit cancelVisit(Long visitId) { return createVisit(999L, 1L, "2026-10-18 10:00"); }
    @Override public MisEscalation createEscalation(Long clientId, String description) {
        return new MisEscalation(777L, clientId, description, "2026-10-17");
    }
}