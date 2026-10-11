package com.clinic.backend.voice;

import java.util.List;
import java.util.Map;

/**
 * Ответ голосового ассистента.
 * appointment заполнен, когда запись реально создана/отменена;
 * proposal заполнен на этапе PENDING_CONFIRMATION (что именно предлагается).
 */
public record VoiceReply(
        Long sessionId,
        String reply,
        String stage,
        boolean needsClarification,
        boolean unsupported,
        boolean handedOff,
        List<String> options,
        Map<String, Object> appointment,
        Map<String, Object> proposal,
        int exchanges,
        int maxExchanges,
        String frame,
        String parser) {
}
