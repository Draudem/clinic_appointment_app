package com.clinic.backend.stt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Управление AEC-объектами по звонкам.
 *
 * <p>У каждого звонка — свой экземпляр {@link WebRtcAec}: AEC3 хранит состояние
 * фильтра, которое нельзя переиспользовать между разными разговорами.
 *
 * <p>Если нативная библиотека не загрузилась — {@code aecAvailable = false},
 * и все вызовы возвращают исходный сигнал (VAD-гейтинг останется как fallback).
 */
@Component
public class AecManager {

    private static final Logger log = LoggerFactory.getLogger(AecManager.class);

    /** Формат: 16 кГц, кадр 10 мс = 160 сэмплов. */
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_MS = 10;

    /** Флаг: удалось ли загрузить нативную библиотеку. */
    private final boolean aecAvailable;

    private final Map<String, WebRtcAec> perCall = new ConcurrentHashMap<>();

    public AecManager() {
        boolean ok;
        try {
            // Проверяем, что libwebrtc_aec.so доступна, попыткой создать экземпляр.
            WebRtcAec probe = new WebRtcAec();
            ok = probe.initialize(SAMPLE_RATE, FRAME_MS);
            if (ok) {
                probe.release();
                log.info("WebRTC AEC доступен: 16 кГц, кадр {} мс, {} сэмплов",
                        FRAME_MS, SAMPLE_RATE * FRAME_MS / 1000);
            } else {
                log.warn("WebRTC AEC не инициализировался — работаем без эхоподавления");
            }
        } catch (UnsatisfiedLinkError e) {
            ok = false;
            log.warn("libwebrtc_aec.so не загрузилась ({}). "
                            + "Проверьте LD_LIBRARY_PATH. Работаем без AEC.",
                    e.getMessage());
        }
        this.aecAvailable = ok;
    }

    public boolean isAvailable() {
        return aecAvailable;
    }

    /** Создать AEC для звонка. Возвращает null, если AEC недоступен. */
    public WebRtcAec create(String callKey) {
        if (!aecAvailable) {
            return null;
        }
        WebRtcAec aec = new WebRtcAec();
        if (!aec.initialize(SAMPLE_RATE, FRAME_MS)) {
            log.warn("Не удалось создать AEC для звонка {}", callKey);
            return null;
        }
        perCall.put(callKey, aec);
        return aec;
    }

    /** Забрать AEC звонка (или null). */
    public WebRtcAec get(String callKey) {
        return perCall.get(callKey);
    }

    /** Удалить и освободить AEC звонка. */
    public void remove(String callKey) {
        WebRtcAec aec = perCall.remove(callKey);
        if (aec != null) {
            aec.release();
        }
    }

    /** Сколько AEC активно — для диагностики. */
    public int activeCount() {
        return perCall.size();
    }
}