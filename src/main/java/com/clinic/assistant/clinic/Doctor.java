package com.clinic.assistant.clinic;

import jakarta.persistence.*;

/** Сотрудник-врач. */
@Entity
@Table(name = "doctor")
public class Doctor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ФИО, как просит фронтенд: «Иванов Пётр Сергеевич». */
    @Column(nullable = false, length = 128)
    private String fio;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "specialty_id")
    private Specialty specialty;

    @Column(nullable = false)
    private boolean active = true;

    protected Doctor() {
    }

    public Doctor(String fio, Specialty specialty) {
        this.fio = fio;
        this.specialty = specialty;
    }

    public Long getId() {
        return id;
    }

    public String getFio() {
        return fio;
    }

    public void setFio(String fio) {
        this.fio = fio;
    }

    public Specialty getSpecialty() {
        return specialty;
    }

    public void setSpecialty(Specialty specialty) {
        this.specialty = specialty;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
