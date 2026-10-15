package com.clinic.backend.voice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DialogueSessionRepository extends JpaRepository<DialogueSession, Long> {

    List<DialogueSession> findByPatientLoginOrderByIdDesc(String patientLogin);

    /** Сессия телефонного звонка по ключу от Asterisk. */
    Optional<DialogueSession> findByCallKey(String callKey);

    /**
     * Стенограммы, у которых истёк срок хранения.
     * Регламент, раздел 8: записи разговоров хранятся не более 90 дней.
     */
    List<DialogueSession> findByExpiresAtBefore(Instant moment);
}