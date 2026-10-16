package com.clinic.backend.stt;

/**
 * JNI-обёртка над WebRTC Audio Processing (AEC3) для полноценного
 * эхоподавления.
 *
 * <p>Зачем: VAD-гейтинг отсекает эхо по времени и не даёт пациенту
 * перебивать ассистента. AEC3 ВЫЧИТАЕТ эхо из входящего сигнала, поэтому
 * пациент может говорить в любой момент, а его голос остаётся чистым.
 *
 * <p>Принцип работы WebRTC AEC:
 * <ol>
 *   <li>{@link #processReverse} — опорный сигнал: то, что ассистент
 *       отправляет в Asterisk (голос Piper).</li>
 *   <li>{@link #processNear} — сигнал микрофона: то, что приходит от
 *       пациента (голос + эхо ассистента).</li>
 *   <li>Внутри AEC вычитает эхо, опираясь на опорный сигнал.</li>
 *   <li>Возвращает очищенный сигнал — только голос пациента.</li>
 * </ol>
 *
 * <p>Формат: 16 кГц, моно, кадры по 10 мс = 160 сэмплов = 320 байт
 * (PCM int16 little-endian).
 */
public final class WebRtcAec {

    static {
        // libwebrtc_aec.so должен лежать в java.library.path
        // или в LD_LIBRARY_PATH
        System.loadLibrary("webrtc_aec");
    }

    /** Хэндл нативного объекта (указатель на AudioProcessing в C++). */
    private long nativeHandle;

    /**
     * Создать экземпляр AEC.
     *
     * @param sampleRate  частота дискретизации (для нас 16000)
     * @param frameMs     размер кадра в миллисекундах (для нас 10)
     * @return true, если инициализация прошла успешно
     */
    public boolean initialize(int sampleRate, int frameMs) {
        nativeHandle = nativeInitialize(sampleRate, frameMs);
        return nativeHandle != 0L;
    }

    /**
     * Передать опорный сигнал (то, что ассистент отправляет в Asterisk).
     * Вызывать ПЕРЕД processNear.
     *
     * @param pcm PCM int16, 160 сэмплов (320 байт)
     */
    public void processReverse(byte[] pcm) {
        if (nativeHandle == 0L) {
            return;
        }
        nativeProcessReverse(nativeHandle, pcm);
    }

    /**
     * Обработать сигнал микрофона (что пришло от пациента).
     *
     * @param pcm PCM int16, 160 сэмплов (320 байт)
     * @return очищенный PCM (тот же размер), либо исходный, если AEC не работает
     */
    public byte[] processNear(byte[] pcm) {
        if (nativeHandle == 0L) {
            return pcm;
        }
        byte[] out = nativeProcessNear(nativeHandle, pcm);
        return out != null ? out : pcm;
    }

    /** Освободить нативные ресурсы. Вызывать в конце звонка. */
    public void release() {
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle);
            nativeHandle = 0L;
        }
    }

    /** Доступен ли AEC (загрузилась ли нативная библиотека). */
    public boolean available() {
        return nativeHandle != 0L;
    }

    // --- native methods ---

    private static native long nativeInitialize(int sampleRate, int frameMs);

    private static native void nativeProcessReverse(long handle, byte[] pcm);

    private static native byte[] nativeProcessNear(long handle, byte[] pcm);

    private static native void nativeRelease(long handle);
}