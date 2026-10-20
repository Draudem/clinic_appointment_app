package com.clinic.backend.appointment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    List<Appointment> findByPatientIdOrderByDateAscTimeAsc(Long patientId);

    Optional<Appointment> findByDoctorIdAndDateAndTimeAndStatus(Long doctorId, LocalDate date, String time, String status);

    /** Активные записи пациента — блокируют удаление пользователя. */
    long countByPatientIdAndStatus(Long patientId, String status);

    /** Отменённые записи удаляются каскадно вместе с пользователем. */
    void deleteByPatientIdAndStatus(Long patientId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Appointment a where a.doctor.id = :doctorId and a.date = :date and a.time = :time")
    List<Appointment> findAndLock(@Param("doctorId") Long doctorId,
                                  @Param("date") LocalDate date,
                                  @Param("time") String time);
}