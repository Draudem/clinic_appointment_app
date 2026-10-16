package com.clinic.backend.stt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Голосовой поток одного звонка: синтез и отправка аудио в сокет.
 *
 * <p>Флаг {@link #isSpeaking()} используется в {@link AsteriskAudioSocketServer}
 * для VAD-гейтинга: пока ассистент говорит (и ещё {@link #ECHO_TAIL_MS} мс после),
 * входящее аудио от клиента игнорируется. Это отсекает акустическое эхо —
 * голос ассистента через динамик телефона попадает в микрофон и без гейтинга
 * распознаётся как «речь клиента».
 *
 * <p>Счётчики {@link #spokenOk()} / {@link #spokenFailed()} — глобальная
 * статистика по всем звонкам, нужна для /api/stt/status.
 */
class CallVoice {

    private static final Logger log = LoggerFactory.getLogger(CallVoice.class);

    /** Протокол AudioSocket жёстко требует PCM 16 bit, 8 кГц, моно. */
    private static final int AUDIO_RATE = 8000;
    private static final int FRAME_BYTES = 320;      // 20 мс
    private static final int FRAME_MS = 20;
    private static final int TYPE_AUDIO = 0x10;

    /**
     * «Хвост тишины» после окончания речи ассистента: столько миллисекунд
     * входящее аудио ещё считается эхом и игнорируется.
     */
    private static final long ECHO_TAIL_MS = 250;

    /** Глобальная статистика: сколько фраз реально прозвучало. */
    private static final AtomicLong SPOKEN_OK = new AtomicLong();
    /** Глобальная статистика: сколько фраз не удалось озвучить. */
    private static final AtomicLong SPOKEN_FAILED = new AtomicLong();

    private final OutputStream out;
    private final ReplySynthesizer synth;
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final CountDownLatch finished = new CountDownLatch(1);

    private volatile boolean closed;
    private volatile IOException failure;

    /** Пока true — ассистент физически пишет кадры в сокет. */
    private volatile boolean speaking = false;
    /** Время последнего кадра речи ассистента (для «хвоста тишины»). */
    private volatile long lastSpokeAt = 0L;

    CallVoice(OutputStream out, ReplySynthesizer synth) {
        this.out = out;
        this.synth = synth;
        Thread t = new Thread(this::run, "call-voice");
        t.setDaemon(true);
        t.start();
    }

    /** Ставит фразу в очередь озвучивания. */
    void enqueue(String text) {
        if (!closed && text != null && !text.isBlank()) {
            queue.add(text);
        }
    }

    /** Ждёт, пока очередь опустеет и последняя фраза дозвучит. */
    boolean awaitFinished(long seconds) {
        long deadline = System.currentTimeMillis() + seconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            if (queue.isEmpty() && !speaking) {
                return true;   // очередь пуста и не говорим — всё, ответ дозвучал
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

    /**
     * Говорит ли ассистент прямо сейчас (или говорил только что — в пределах
     * {@link #ECHO_TAIL_MS}). Пока true, входящее аудио игнорируется.
     */
    public boolean isSpeaking() {
        if (speaking) {
            return true;
        }
        return (System.currentTimeMillis() - lastSpokeAt) < ECHO_TAIL_MS;
    }

    /** Успешно озвученных фраз (для /api/stt/status). */
    static long spokenOk() {
        return SPOKEN_OK.get();
    }

    /** Фраз, которые не удалось озвучить (для /api/stt/status). */
    static long spokenFailed() {
        return SPOKEN_FAILED.get();
    }

    /**
     * Разбивает текст на предложения — используется для подсчёта «фраз»
     * в логе хода диалога ({@code ответ из N фр.}).
     */
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
                // Если очередь пуста — просто продолжаем ждать.
                // НЕ выходим из цикла: новые фразы могут появиться позже.
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
        // Вниз до 8 кГц с фильтром: без него голос пищит (алиасинг).
        float[] at8k = PcmConverter.downsample(raw, synth.sampleRate(), AUDIO_RATE);
        byte[] pcm = PcmResampler.floatToBytes(at8k);

        speaking = true;   // с этого момента игнорируем микрофон
        long start = System.currentTimeMillis();
        try {
            for (int offset = 0; offset < pcm.length && !closed; offset += FRAME_BYTES) {
                int len = Math.min(FRAME_BYTES, pcm.length - offset);
                out.write(TYPE_AUDIO);
                out.write((len >> 8) & 0xFF);      // длина в заголовке — big endian
                out.write(len & 0xFF);
                out.write(pcm, offset, len);
                out.flush();
                sleepFrame();
            }
        } catch (IOException e) {
            // Соединение оборвалось посередине фразы — дальше озвучивать некуда.
            failure = e;
            closed = true;
            SPOKEN_FAILED.incrementAndGet();
            log.warn("Озвучка прервана ошибкой записи: {}", e.getMessage());
            return;
        } finally {
            // Сбрасываем флаг ТОЛЬКО здесь, чтобы при исключении не «залипнуть».
            speaking = false;
            lastSpokeAt = System.currentTimeMillis();
        }
        SPOKEN_OK.incrementAndGet();
        // Формат собираем строкой: в SLF4J нет {:.1f}, там только {} с toString().
        log.info("Озвучено " + String.format("%.1f", pcm.length / 2.0 / AUDIO_RATE)
                + " с за " + (System.currentTimeMillis() - start) + " мс: «"
                + (text.length() > 40 ? text.substring(0, 40) + "…" : text) + "»");
    }

    private void sleepFrame() {
        try {
            Thread.sleep(FRAME_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closed = true;
        }
    }

    /** Прекращает озвучивание (при разрыве связи). */
    void close() {
        closed = true;
    }

    IOException failure() {
        return failure;
    }
}