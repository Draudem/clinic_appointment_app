package com.clinic.backend.stt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Голосовой поток одного звонка: синтез и отправка аудио в сокет.
 *
 * ЗАЧЕМ ОТДЕЛЬНЫЙ ПОТОК
 * ----------------------
 * Поток чтения в AsteriskAudioSocketServer непрерывен: он принимает кадры звонящего,
 * режет фразы по тишине и РАСПОЗНАЁТ их. Если синтез и отправка выполнялись бы там же,
 * чтение останавливалось бы на все 6.7 секунды приветствия — и то, что клиент сказал
 * поверх, пропало бы.
 *
 * ЗДЕСЬ ПОТОК НИКОГДА НЕ УМИРАЕТ
 * ------------------------------
 * Раньше цикл был {@code while ((text = queue.poll(200, MS)) != null)}: на пустой
 * очереди poll возвращал null, цикл выходил, поток умирал. Позже enqueue() клал
 * фразу в очередь, но читать её было уже некому, awaitFinished() возвращался
 * мгновенно, и сервер вешал трубку через TTS_CLOSE_DELAY_SECONDS. Звонящий слышал
 * только приветствие, а «Спасибо, записываю» пропадало — это и было причиной
 * жалоб напарника. Теперь поток живёт до close() и ждёт фразы бесконечно,
 * а «дозвучало» определяется счётчиком непрочитанных фраз, а не латчем.
 *
 * ОТПРАВКА В ТЕМПЕ РЕАЛЬНОГО ВРЕМЕНИ
 * ----------------------------------
 * Кадр 320 байт = 20 мс на 8 кГц, поэтому между кадрами спим 20 мс. Если вывалить
 * всю фразу одним куском, Asterisk возьмёт её в буфер и начнёт отдавать в канал
 * с задержкой, а закрытие сокета может оборвать конец фразы.
 */
class CallVoice {

    private static final Logger log = LoggerFactory.getLogger(CallVoice.class);

    /** Протокол AudioSocket жёстко требует PCM 16 bit, 8 кГц, моно. */
    private static final int AUDIO_RATE = 8000;
    private static final int FRAME_BYTES = 320;      // 20 мс
    private static final int FRAME_MS = 20;
    private static final int TYPE_AUDIO = 0x10;

    private final OutputStream out;
    private final ReplySynthesizer synth;
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();

    /** Сколько фраз поставлено в очередь, но ещё не дозвучало. */
    private final AtomicInteger pending = new AtomicInteger();

    private volatile boolean closed;
    private volatile IOException failure;

    CallVoice(OutputStream out, ReplySynthesizer synth) {
        this.out = out;
        this.synth = synth;
        Thread t = new Thread(this::run, "call-voice");
        t.setDaemon(true);
        t.start();
    }

    /** Ставит фразу в очередь озвучивания. */
    void enqueue(String text) {
        if (closed || text == null || text.isBlank()) {
            return;
        }
        pending.incrementAndGet();
        queue.add(text);
    }

    /**
     * Ждёт, пока все поставленные фразы дозвучат.
     * Возвращает true, если очередь опустела за отведённое время.
     */
    boolean awaitFinished(long seconds) {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (pending.get() == 0 && queue.isEmpty()) {
                return true;
            }
            if (failure != null || closed) {
                return false;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return pending.get() == 0;
    }

    /**
     * Тело потока: ждёт фразы бесконечно и умирает только по close().
     * Пустая очередь — это не причина завершаться.
     */
    private void run() {
        try {
            while (!closed) {
                String text = queue.poll(200, TimeUnit.MILLISECONDS);
                if (text == null) {
                    continue;          // просто ждём следующую фразу
                }
                speak(text);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // Непрочитанные фразы не должны помешать проверке завершения.
            queue.clear();
            pending.set(0);
        }
    }

    private void speak(String text) {
        try {
            float[] raw = synth.synthesize(text);
            if (raw == null || raw.length == 0) {
                log.debug("Синтез вернул пусто — фраза пропущена");
                return;
            }
            // Вниз до 8 кГц с фильтром: без него голос пищит (алиасинг).
            float[] at8k = PcmConverter.downsample(raw, synth.sampleRate(), AUDIO_RATE);
            byte[] pcm = PcmResampler.floatToBytes(at8k);

            long start = System.currentTimeMillis();
            for (int offset = 0; offset < pcm.length && !closed; offset += FRAME_BYTES) {
                int len = Math.min(FRAME_BYTES, pcm.length - offset);
                out.write(TYPE_AUDIO);
                out.write((len >> 8) & 0xFF);      // длина в заголовке — big endian
                out.write(len & 0xFF);
                out.write(pcm, offset, len);
                out.flush();
                sleepFrame();
            }
            // Формат собираем строкой: в SLF4J нет {:.1f}, там только {} с toString().
            log.info("Озвучено " + String.format("%.1f", pcm.length / 2.0 / AUDIO_RATE)
                    + " с за " + (System.currentTimeMillis() - start) + " мс: «"
                    + (text.length() > 40 ? text.substring(0, 40) + "…" : text) + "»");
        } catch (IOException e) {
            // Соединение оборвалось посередине фразы — дальше озвучивать некуда.
            failure = e;
            closed = true;
            log.warn("Озвучка прервана ошибкой записи: {}", e.getMessage());
        } finally {
            pending.decrementAndGet();
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

    /** Прекращает озвучивание (при разрыве связи). */
    void close() {
        closed = true;
    }

    /** Есть ли неозвученные фразы — диагностика. */
    int pendingCount() {
        return pending.get();
    }

    IOException failure() {
        return failure;
    }
}