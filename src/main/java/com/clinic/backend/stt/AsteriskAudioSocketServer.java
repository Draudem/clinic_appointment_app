package com.clinic.backend.stt;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Приём звонка от Asterisk по протоколу AudioSocket и распознавание речи на сервере.
 *
 * ПРОТОКОЛ (проверен по документации Asterisk)
 * --------------------------------------------
 * AudioSocket — это обычный TCP, НЕ HTTP. Поэтому отдельный порт (по умолчанию 8091)
 * и никаких @RestController: HTTP-слой обслуживать сырой поток не умеет.
 *
 * Кадры — TLV: type:uint8, length:uint16 (BIG endian, сетевой порядок), payload.
 *   0x00 Terminate  — звонок завершён, payload пустой
 *   0x01 UUID       — 16 байт, Asterisk шлёт сразу после коннекта
 *   0x10 Audio      — PCM 16 bit signed, 8 кГц, моно, LITTLE endian
 *   0xFF Error      — код ошибки
 *
 * ВНИМАНИЕ на две разные разрядности в одном кадре: заголовок длины — big endian,
 * а сами аудиосэмплы — little endian. Это частая ошибка.
 *
 * ФОРМАТ АУДИО
 * ------------
 * Asterisk отдаёт строго 8 кГц (параметр не настраивается by design), а GigaAM
 * обучена на 16 кГц. Отсюда обязательный ресемплинг x2 — без него модель получает
 * вдвое медленную речь и молча выдаёт пустоту.
 *
 * ЗАЧЕМ ЭТОТ КЛАСС ВМЕСТО ОБЫЧНОГО @RestController
 * -----------------------------------------------
 * Ровно затем же, ради чего в проекте сделаны порты CommandParser/SpeechToText:
 * чтобы этап можно было заменить, не трогая остальные. Здесь собраны ТОЛЬКО приём
 * байтов, ресемплинг и вызов распознавания. Всё, что касается записи к врачу,
 * живёт в DialogueManager и не изменится, когда появится настоящий TTS.
 */
@Component
@ConditionalOnProperty(prefix = "app.stt", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AsteriskAudioSocketServer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AsteriskAudioSocketServer.class);

    private static final int TYPE_TERMINATE = 0x00;
    private static final int TYPE_UUID = 0x01;
    private static final int TYPE_AUDIO = 0x10;

    /** Asterisk шлёт ~320 байт аудио за раз (20 мс на 8 кГц). */
    private static final int TYPICAL_CHUNK = 320;
    /** Пауза сокета, после которой считаем, что звонок повис. */
    private static final int SOCKET_TIMEOUT_MS = 120_000;

    private final SpeechRecognizer recognizer;
    private final ReplySynthesizer synthesizer;
    private final SttProperties props;
    private final TtsProperties ttsProps;
    private final TranscriptStore transcriptStore;

    private ServerSocket serverSocket;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "audiosocket-call");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;
    private final AtomicLong callCounter = new AtomicLong();

    public AsteriskAudioSocketServer(SpeechRecognizer recognizer,
                                     ReplySynthesizer synthesizer,
                                     SttProperties props,
                                     TtsProperties ttsProps,
                                     TranscriptStore transcriptStore) {
        this.recognizer = recognizer;
        this.synthesizer = synthesizer;
        this.props = props;
        this.ttsProps = ttsProps;
        this.transcriptStore = transcriptStore;
    }

    /** Поднимает порт. Ошибка bind не должна ронять весь бэкенд — ловим и логируем. */
    @PostConstruct
    void start() {
        if (!recognizer.ready()) {
            log.warn("STT выключена (модель не загружена: {}) — AudioSocket-порт {} не поднимаем",
                    recognizer.error(), props.getAudiosocketPort());
            running = false;
            return;
        }
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("0.0.0.0", props.getAudiosocketPort()));
        } catch (IOException e) {
            log.error("Не удалось занять порт AudioSocket {}: {}", props.getAudiosocketPort(), e.getMessage());
            running = false;
            return;
        }
        Thread acceptor = new Thread(this, "audiosocket-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        log.info("AudioSocket слушает порт {} (движок {}, ответы {})",
                props.getAudiosocketPort(), recognizer.name(), synthesizer.name());
    }

    @Override
    public void run() {
        while (running) {
            Socket socket = null;
            try {
                socket = serverSocket.accept();
                Socket call = socket;
                workers.submit(() -> handleCall(call));
            } catch (IOException e) {
                if (running) {
                    log.error("Ошибка приёма подключения AudioSocket: {}", e.getMessage());
                }
            }
        }
    }

    /** Обрабатывает один звонок целиком. Сокет закрывается здесь же, в finally. */
    private void handleCall(Socket socket) {
        long callId = callCounter.incrementAndGet();
        String remote = socket.getRemoteSocketAddress() == null
                ? "?" : socket.getRemoteSocketAddress().toString();
        log.info("Звонок #{} подключён ({})", callId, remote);

        PcmResampler resampler = new PcmResampler();
        // Окно RMS ~ 30 мс — короткое, чтобы границы фраз не «залипали».
        UtteranceSegmenter segmenter = new UtteranceSegmenter(
                props.getSilenceRmsThreshold(), (int) (16000 * 0.03));
        segmenter.silenceSeconds = (float) props.getSilenceSeconds();
        int minSamples = (int) (16000 * props.getMinUtteranceSeconds());
        int maxSamples = (int) (16000 * props.getMaxUtteranceSeconds());
        String callUuid = "call-" + callId;
        CallVoice voice = null;
        // Ответили ли уже: первая распознанная фраза завершает сценарий звонка.
        boolean answered = false;

        try {
            socket.setTcpNoDelay(true);   // без этого Asterisk рвёт кадры по Nagle
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            // Голосовой поток живёт всё время звонка. Он синтезирует и отправляет,
            // а поток чтения ниже продолжает принимать и распознавать речь клиента —
            // в том числе пока ассистент говорит.
            voice = new CallVoice(out, synthesizer);
            voice.enqueue(ttsProps.getGreeting());
            log.info("Звонок #{}: озвучено приветствие, синтез {}", callId, synthesizer.name());

            while (running) {
                int type = in.read();
                if (type < 0) {
                    break;                       // Asterisk закрыл соединение
                }
                // length — uint16 в СЕТЕВОМ порядке (big endian)
                int length = in.readUnsignedShort();
                if (length < 0 || length > 1_000_000) {
                    log.warn("Звонок #{}: неправдоподобная длина кадра {} — разрываем", callId, length);
                    break;
                }
                byte[] payload = new byte[length];
                in.readFully(payload);

                switch (type) {
                    case TYPE_TERMINATE -> {
                        log.info("Звонок #{}: Terminate", callId);
                        flushUtterance(callId, callUuid, segmenter, minSamples);
                        return;
                    }
                    case TYPE_UUID -> {
                        if (payload.length == 16) {
                            callUuid = new UUID(bytesToLong(payload, 0), bytesToLong(payload, 8)).toString();
                        }
                        log.info("Звонок #{}: UUID {}", callId, callUuid);
                    }
                    case TYPE_AUDIO -> {
                        // payload — little-endian PCM int16 8 кГц
                        float[] at8k = PcmResampler.bytesToFloat(payload, 0, payload.length);
                        float[] at16k = resampler.resample(at8k);

                        int start = 0;
                        while (start < at16k.length) {
                            int end = Math.min(at16k.length, start + TYPICAL_CHUNK * 2);
                            float[] piece = java.util.Arrays.copyOfRange(at16k, start, end);
                            boolean finished = segmenter.feed(piece);
                            start = end;
                            if (finished || segmenter.bufferedSeconds() >= props.getMaxUtteranceSeconds()) {
                                // Фраза клиента. Распознаём и пишем текст ВСЕГДА,
                                // в том числе пока ассистент ещё договаривает.
                                String said = flushUtterance(callId, callUuid, segmenter, minSamples);
                                if (said != null && !said.isBlank() && !answered) {
                                    // Первая распознанная фраза — отвечаем и завершаем звонок.
                                    answered = true;
                                    voice.enqueue(ttsProps.getReplyOnSilence());
                                    log.info("Звонок #{}: отвечаем «{}» и ждём окончания озвучки",
                                            callId, ttsProps.getReplyOnSilence());
                                    voice.awaitFinished(30);
                                    sleepQuietly(ttsProps.getCloseDelaySeconds() * 1000L);
                                    log.info("Звонок #{}: фраза дозвучала, закрываем соединение", callId);
                                    return;
                                }
                            }
                        }
                    }
                    default -> log.debug("Звонок #{}: неизвестный тип кадра 0x{}", callId, Integer.toHexString(type));
                }
            }
        } catch (SocketTimeoutException e) {
            log.warn("Звонок #{}: таймаут {}", callId, e.getMessage());
        } catch (SocketException e) {
            log.info("Звонок #{}: соединение закрыто ({})", callId, e.getMessage());
        } catch (IOException e) {
            log.error("Звонок #{}: ошибка ввода-вывода: {}", callId, e.getMessage());
        } catch (RuntimeException e) {
            // Иначе исключение уйдёт в Future и поток молча умрёт — звонок «исчезнет».
            log.error("Звонок #{}: непредвиденная ошибка разбора потока", callId, e);
        } finally {
            if (voice != null) {
                voice.close();
            }
            try {
                socket.close();
            } catch (IOException ignored) {
                // сокет уже мог быть закрыт клиентом
            }
            log.info("Звонок #{} завершён, распознано фраз: {}", callId,
                    transcriptStore.countFor(callUuid));
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Распознаёт накопленную фразу и возвращает её текст.
     *
     * Текст пишется в лог и в хранилище ВСЕГДА, когда что-то распознано, —
     * в том числе в тот момент, когда ассистент ещё договаривает приветствие.
     * Именно ради этого озвучивание вынесено в отдельный поток.
     *
     * @return распознанный текст или пустая строка
     */
    private String flushUtterance(long callId, String callUuid,
                                 UtteranceSegmenter segmenter, int minSamples) {
        float[] utterance = segmenter.takeUtterance();
        if (utterance.length < minSamples) {
            log.debug("Звонок #{}: {} сэмплов (минимум {}) — тишина, не речь",
                    callId, utterance.length, minSamples);
            return "";                                  // щелчок в линии, не речь
        }
        String text = recognizer.transcribe(utterance);
        if (text == null || text.isBlank()) {
            log.info("Звонок #{}: речь не распознана ({} сэмплов)", callId, utterance.length);
            transcriptStore.add(callUuid, "", (utterance.length / 16000.0f));
            return "";
        }
        log.info("Звонок #{} РАСПОЗНАНО: «{}»", callId, text);
        transcriptStore.add(callUuid, text, utterance.length / 16000.0f);
        return text;
    }

    /** Отправляет аудио в Asterisk кадрами по 320 байт (= 20 мс на 8 кГц). */
    private static long bytesToLong(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xFFL);
        }
        return v;
    }

    public boolean isListening() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    @PreDestroy
    void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // закрываемся — ничего не делаем
        }
        workers.shutdownNow();
    }

    /** Бины STT: распознаватель, синтезатор, хранилище расшифровок. */
    @Configuration
    static class Config {

        @Bean
        SpeechRecognizer speechRecognizer(SttProperties props) {
            return new SherpaSttEngine(props.getModelDir(), props.getNumThreads());
        }

        /**
         * Синтез речи. Если модель Piper не нашлась — молча откатываемся на заглушку:
         * звонки продолжают приниматься и распознаваться, просто звонящий слышит тишину.
         */
        @Bean
        ReplySynthesizer replySynthesizer(TtsProperties ttsProps) {
            if (ttsProps.isEnabled()) {
                SherpaTtsEngine piper = new SherpaTtsEngine(
                        ttsProps.getModelDir(), ttsProps.getNumThreads(), ttsProps.getSpeed());
                if (piper.available()) {
                    return piper;
                }
                log.warn("TTS недоступен ({}), работает заглушка с тишиной", piper.error());
            } else {
                log.info("TTS выключен (app.tts.enabled=false), работает заглушка с тишиной");
            }
            return new SilentReplySynthesizer();
        }

        @Bean
        TranscriptStore transcriptStore() {
            return new TranscriptStore();
        }
    }
}