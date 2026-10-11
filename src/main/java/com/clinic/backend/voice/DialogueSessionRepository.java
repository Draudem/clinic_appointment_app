package com.clinic.backend.voice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DialogueSessionRepository extends JpaRepository<DialogueSession, Long> {

    List<DialogueSession> findByPatientLoginOrderByIdDesc(String patientLogin);
}
