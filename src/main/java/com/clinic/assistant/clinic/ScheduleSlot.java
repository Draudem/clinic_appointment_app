package com.clinic.assistant.clinic;

import jakarta.persistence.*;

import java.time.LocalTime;

/**
 * Еженедельное окно приёма врача: день недели + время начала.
 *
 * <p>Например, «Пн/Ср/Пт в 09:00» — три записи с weekday = 1, 3, 5
 * (ISO: 1 = Понедельник … 7 = Воскресенье).</p>
 */
@Entity
@Table(name = "schedule_slot")
public class ScheduleSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id")
    private Doctor doctor;

    /** День недели по ISO: 1 = Пн … 7 = Вс. */
    @Column(nullable = false)
    private int weekday;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes = 30;

    protected ScheduleSlot() {
    }

    public ScheduleSlot(Doctor doctor, int weekday, LocalTime startTime) {
        this(doctor, weekday, startTime, 30);
    }

    public ScheduleSlot(Doctor doctor, int weekday, LocalTime startTime, int durationMinutes) {
        this.doctor = doctor;
        this.weekday = weekday;
        this.startTime = startTime;
        this.durationMinutes = durationMinutes;
    }

    public Long getId() {
        return id;
    }

    public Doctor getDoctor() {
        return doctor;
    }

    public int getWeekday() {
        return weekday;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }
}
