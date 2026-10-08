package com.clinic.assistant.clinic;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DoctorRepository extends JpaRepository<Doctor, Long> {

    List<Doctor> findByActiveTrue();

    List<Doctor> findByActiveTrueAndSpecialtyId(Long specialtyId);

    List<Doctor> findBySpecialtyId(Long specialtyId);
}
