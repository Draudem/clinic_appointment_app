package com.clinic.assistant.clinic;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    boolean existsByDoctorIdAndDateAndStartTimeAndStatus(Long doctorId,
                                                         LocalDate date,
                                                         java.time.LocalTime startTime,
                                                         AppointmentStatus status);

    List<Appointment> findByStatusAndDateBetween(AppointmentStatus status, LocalDate from, LocalDate to);

    List<Appointment> findByStatusAndDateBetweenAndDoctorIdIn(AppointmentStatus status,
                                                              LocalDate from,
                                                              LocalDate to,
                                                              Collection<Long> doctorIds);

    List<Appointment> findByPatientIdAndStatusOrderByDateAscStartTimeAsc(Long patientId, AppointmentStatus status);

    List<Appointment> findByStatusOrderByDateAscStartTimeAsc(AppointmentStatus status);

    Optional<Appointment> findByIdAndPatientId(Long id, Long patientId);

    @Query("""
            SELECT a FROM Appointment a
            WHERE a.status = :status AND a.date = :date
              AND (:doctorId IS NULL OR a.doctor.id = :doctorId)
            ORDER BY a.startTime
            """)
    List<Appointment> findForDate(@Param("status") AppointmentStatus status,
                                  @Param("date") LocalDate date,
                                  @Param("doctorId") Long doctorId);
}
