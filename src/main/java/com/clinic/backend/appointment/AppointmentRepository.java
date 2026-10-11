package com.clinic.backend.appointment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    List<Appointment> findByPatientIdOrderByDateAscTimeAsc(Long patientId);

    Optional<Appointment> findByDoctorIdAndDateAndTimeAndStatus(Long doctorId, LocalDate date, String time, String status);
}
