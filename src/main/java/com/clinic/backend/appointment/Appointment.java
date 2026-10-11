package com.clinic.backend.appointment;

import com.clinic.backend.doctor.Doctor;
import com.clinic.backend.user.User;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/** Запись пациента к врачу на конкретные дата/время. */
@Entity
@Table(name = "appointments")
// Констрейнт UNIQUE(doctor_id,date,time) не ставим: отмена записи мягкая
// (status=CANCELLED), строка остаётся, и слот должен стать свободен снова.
// Защита от двойного бронирования — проверка CONFIRMED в AppointmentService.
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User patient;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id")
    private Doctor doctor;

    @Column(nullable = false)
    private LocalDate date;

    /** HH:mm — время приёма. */
    @Column(nullable = false, length = 5)
    private String time;

    @Column(nullable = false, length = 20)
    private String status; // CONFIRMED | CANCELLED

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected Appointment() {
        // for JPA
    }

    public Appointment(User patient, Doctor doctor, LocalDate date, String time) {
        this.patient = patient;
        this.doctor = doctor;
        this.date = date;
        this.time = time;
        this.status = "CONFIRMED";
    }

    public Long getId() {
        return id;
    }

    public User getPatient() {
        return patient;
    }

    public Doctor getDoctor() {
        return doctor;
    }

    public LocalDate getDate() {
        return date;
    }

    public String getTime() {
        return time;
    }

    public String getStatus() {
        return status;
    }

    public void cancel() {
        this.status = "CANCELLED";
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
