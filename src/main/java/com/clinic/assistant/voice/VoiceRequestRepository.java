package com.clinic.assistant.voice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VoiceRequestRepository extends JpaRepository<VoiceRequest, Long> {

    List<VoiceRequest> findTop50ByOrderByCreatedAtDesc();
}
