package com.clinic.backend.doctor;

import java.util.List;

/**
 * DTO врача для контрактов фронтенда и мобильного приложения:
 * {id, fio, specialty, time, days: [0..6]}.
 */
public record DoctorDto(
        Long id,
        String fio,
        String specialty,
        String time,
        List<Integer> days) {

    public static DoctorDto from(Doctor d) {
        return new DoctorDto(d.getId(), d.getFio(), d.getSpecialty(), d.getTime(), d.getDaysList());
    }
}
