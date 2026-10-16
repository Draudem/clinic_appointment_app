package com.clinic.backend.stt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Голосовой поток одного звонка: синтез и отправка аудио в сокет.
 *
 * <p>Каждый отправленный кадр передаётся в {@link WebRtcAec} как опорный
 * сигнал. AEC вычитает эхо этого кадра из микрофонного сигнала.
 *
 * <p>Перебивание ассистента НЕ реализовано — VAD-гейтинг снят, AEC работает
 * в фоне, речь пациента обрабатывается после фразы ассистента.
 */
class CallVoice {

    private static final Logger log = LoggerFactory.getLogger(CallVoice.class);

    private static final int AUDIO_RATE = 8000;
    private static final int FRAME_BYTES = 320;
    private static final int FRAME_MS = 20;
    private static final int TYPE_AUDIO = 0x10;

    /** Размер кадра AEC: 10 мс при 16 кГц = 160 сэмплов. */
    private static final int AEC_FRAME_SAMPLES = 160;

    private static final AtomicLong SPOKEN_OK = new AtomicLong();
    private static final AtomicLong SPOKEN_FAILED = new AtomicLong();

    private final OutputStream out;
    private final ReplySynthesizer synth;
    private final WebRtcAec aec;
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final CountDownLatch finished = new CountDownLatch(1);
    private final PcmResampler reverseResampler = new PcmResampler();

    private volatile boolean closed;
    private volatile IOException failure;
    private volatile boolean speaking = false;

    CallVoice(OutputStream out, ReplySynthesizer synth, WebRtcAec aec) {
        this.out = out;
        this.synth = synth;
        this.aec = aec;
        Thread t = new Thread(this::run, "call-voice");
        t.setDaemon(true);
        t.start();
    }

    void enqueue(String text) {
        if (!closed && text != null && !text.isBlank()) {
            queue.add(text);
        }
    }

    boolean awaitFinished(long seconds) {
        long deadline = System.currentTimeMillis() + seconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            if (queue.isEmpty() && !speaking) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public boolean isSpeaking() {
        return speaking;
    }

    static long spokenOk() {
        return SPOKEN_OK.get();
    }

    static long spokenFailed() {
        return SPOKEN_FAILED.get();
    }

    static List<String> splitSentences(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : text.split("(?<=[.!?])\\s+")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                result.add(s);
            }
        }
        return result;
    }

    private void run() {
        try {
            while (!closed) {
                String text = queue.poll(200, TimeUnit.MILLISECONDS);
                if (text != null) {
                    speak(text);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            finished.countDown();
        }
    }

    private void speak(String text) {
        float[] raw;
        try {
            raw = synth.synthesize(text);
        } catch (Throwable e) {
            SPOKEN_FAILED.incrementAndGet();
            log.warn("Синтез упал: {}", e.getMessage());
            return;
        }
        if (raw == null || raw.length == 0) {
            SPOKEN_FAILED.incrementAndGet();
            log.debug("Синтез вернул пусто — фраза пропущена");
            return;
        }
        float[] at8k = PcmConverter.downsample(raw, synth.sampleRate(), AUDIO_RATE);
        byte[] pcm = PcmResampler.floatToBytes(at8k);

        speaking = true;
        long start = System.currentTimeMillis();
        try {
            for (int offset = 0; offset < pcm.length && !closed; offset += FRAME_BYTES) {
                int len = Math.min(FRAME_BYTES, pcm.length - offset);
                if (aec != null) {
                    byte[] frame = Arrays.copyOfRange(pcm, offset, offset + len);
                    feedAecReverse(frame);
                }
                out.write(TYPE_AUDIO);
                out.write((len >> 8) & 0xFF);
                out.write(len & 0xFF);
                out.write(pcm, offset, len);
                out.flush();
                sleepFrame();
            }
        } catch (IOException e) {
            failure = e;
            closed = true;
            SPOKEN_FAILED.incrementAndGet();
            log.warn("Озвучка прервана ошибкой записи: {}", e.getMessage());
            return;
        } finally {
            speaking = false;
        }
        SPOKEN_OK.incrementAndGet();
        log.info("Озвучено " + String.format("%.1f", pcm.length / 2.0 / AUDIO_RATE)
                + " с за " + (System.currentTimeMillis() - start) + " мс: «"
                + (text.length() > 40 ? text.substring(0, 40) + "…" : text) + "»");
    }

    /** Опорный сигнал для AEC: кадр 8 кГц -> 16 кГц, разрезанный на кадры 10 мс. */
    private void feedAecReverse(byte[] frame8k) {
        float[] samples8k = PcmResampler.bytesToFloat(frame8k, 0, frame8k.length);
        float[] samples16k = reverseResampler.resample(samples8k);

        int total = samples16k.length;
        for (int s = 0; s < total; s += AEC_FRAME_SAMPLES) {
            int end = Math.min(s + AEC_FRAME_SAMPLES, total);
            float[] part = Arrays.copyOfRange(samples16k, s, end);
            if (part.length < AEC_FRAME_SAMPLES) {
                float[] padded = new float[AEC_FRAME_SAMPLES];
                System.arraycopy(part, 0, padded, 0, part.length);
                part = padded;
            }
            aec.processReverse(PcmResampler.floatToBytes(part));
        }
    }

    private void sleepFrame() {
        try {
            Thread.sleep(FRAME_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closed = true;
        }
    }

    void close() {
        closed = true;
    }

    IOException failure() {
        return failure;
    }
}