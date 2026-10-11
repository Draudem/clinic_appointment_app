package com.clinic.backend.doctor;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DoctorRepository extends JpaRepository<Doctor, Long> {

    List<Doctor> findAllByOrderByFioAsc();

    List<Doctor> findBySpecialty(String specialty);

    boolean existsByFio(String fio);
}
