package com.clinic.backend.stt;

import com.clinic.backend.voice.CallDialogueService;
import com.clinic.backend.voice.Turn;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Приём звонка от Asterisk по протоколу AudioSocket и распознавание речи на сервере.
 *
 * <p>ПРОТОКОЛ (проверен по документации Asterisk)
 * <ul>
 *   <li>AudioSocket — обычный TCP, НЕ HTTP. Отдельный порт (по умолчанию 8091).</li>
 *   <li>Кадры TLV: type:uint8, length:uint16 (BIG endian), payload.</li>
 *   <li>0x00 Terminate, 0x01 UUID (16 байт), 0x10 Audio (PCM 16 bit 8 кГц LITTLE endian).</li>
 * </ul>
 *
 * <p>VAD-ГЕЙТИНГ: пока ассистент говорит (см. {@link CallVoice#isSpeaking()}),
 * входящее аудио не скармливается в {@link UtteranceSegmenter}. Это отсекает
 * акустическое эхо — голос ассистента через динамик телефона попадает
 * в микрофон и без гейтинга распознаётся как «речь клиента».
 *
 * <p>ДИАГНОСТИКА (временно, для наладки STT):
 * <ul>
 *   <li>{@code AUDIO_RX #N}: первые 20 кадров каждого звонка — bytes, rms, speaking, first16.</li>
 *   <li>{@code VAD}: первые 50 окон {@link UtteranceSegmenter} — rms, silenceRun, speechStarted.</li>
 *   <li>{@code TTS_STATE}: переходы {@link CallVoice#isSpeaking()} true→false / false→true.</li>
 *   <li>{@code STT_RAW}: каждые 5 секунд весь накопленный буфер уходит в STT, минуя VAD.
 *       Показывает, работает ли GigaAM на шуме. Если {@code STT_RAW} осмысленный —
 *       проблема только в VAD-пороге; если мусор — нужен AEC/denoise.</li>
 * </ul>
 * Все диагностические логи — {@code ERROR}, чтобы попасть в вывод при любом уровне.
 * После наладки — убрать.
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
    private final CallDialogueService dialogue;

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
                                     TranscriptStore transcriptStore,
                                     CallDialogueService dialogue) {
        this.recognizer = recognizer;
        this.synthesizer = synthesizer;
        this.props = props;
        this.ttsProps = ttsProps;
        this.transcriptStore = transcriptStore;
        this.dialogue = dialogue;
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
        String callUuid = "call-" + callId;
        CallVoice voice = null;
        int turns = 0;

        // --- ДИАГНОСТИКА ---
        int audioFrameCount = 0;
        int vadWindowCount = 0;
        int ttsTransitionCount = 0;
        boolean prevSpeaking = false;
        // STT_RAW: независимый буфер для принудительной отправки в STT каждые 5 сек.
        // UtteranceSegmenter.takeUtterance() НЕ вызываем, чтобы не обнулить VAD.
        List<Float> rawBuffer = new ArrayList<>();
        int rawBufferCounter = 0;
        final int RAW_FRAMES = 500;   // 500 кадров × 10 мс = 5 сек

        try {
            socket.setTcpNoDelay(true);   // без этого Asterisk рвёт кадры по Nagle
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            // Голосовой поток живёт всё время звонка: синтезирует и отправляет,
            // а поток чтения продолжает принимать и распознавать речь клиента.
            voice = new CallVoice(out, synthesizer);
            voice.enqueue(dialogue.greeting());
            log.info("Звонок #{}: озвучено приветствие, синтез {}", callId, synthesizer.name());

            final CallVoice callVoice = voice;

            while (running) {
                int type = in.read();
                if (type < 0) {
                    break;
                }
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
                            dialogue.onCallStarted(callUuid, callerPhone(socket));
                        }
                        log.info("Звонок #{}: UUID {}", callId, callUuid);
                    }
                    case TYPE_AUDIO -> {
                        // ДИАГНОСТИКА: первые 20 кадров звонка
                        if (audioFrameCount++ < 20) {
                            double rms = rms(payload);
                            log.error("AUDIO_RX #{}: bytes={}, rms≈{}, speaking={}, first16={}",
                                    audioFrameCount, payload.length,
                                    String.format("%.4f", rms),
                                    callVoice.isSpeaking(),
                                    Arrays.toString(toInt16(payload, 16)));
                        }

                        // payload — little-endian PCM int16 8 кГц
                        float[] at8k = PcmResampler.bytesToFloat(payload, 0, payload.length);
                        float[] at16k = resampler.resample(at8k);

                        // STT_RAW: копим ВСЁ аудио (включая шум и эхо) в отдельный буфер.
                        // Раз в 5 сек — отправляем в STT. Это НЕ влияет на VAD.
                        for (float v : at16k) {
                            rawBuffer.add(v);
                        }
                        if (++rawBufferCounter >= RAW_FRAMES && !rawBuffer.isEmpty()) {
                            rawBufferCounter = 0;
                            float[] raw = new float[rawBuffer.size()];
                            for (int i = 0; i < raw.length; i++) {
                                raw[i] = rawBuffer.get(i);
                            }
                            rawBuffer.clear();
                            String text = recognizer.transcribe(raw);
                            log.error("STT_RAW: {} сэмплов ({} с), «{}»",
                                    raw.length,
                                    String.format("%.2f", raw.length / 16000.0),
                                    text);
                        }

                        // ── VAD-ГЕЙТИНГ: пока ассистент говорит, не слушаем микрофон ──
                        boolean nowSpeaking = callVoice.isSpeaking();
                        if (ttsTransitionCount < 20 && nowSpeaking != prevSpeaking) {
                            log.error("TTS_STATE #{}: speaking {} -> {}",
                                    ++ttsTransitionCount, prevSpeaking, nowSpeaking);
                            prevSpeaking = nowSpeaking;
                        }

                        if (!nowSpeaking && prevSpeaking) {
                            segmenter.reset();
                            log.error("VAD_RESET: после TTS, буфер очищен");
                        }

                        if (nowSpeaking) {
                            // Сбрасываем накопитель, чтобы эхо не «прилипло»
                            // к следующей фразе клиента.
                            //segmenter.reset();
                            continue;
                        }

                        int start = 0;
                        while (start < at16k.length) {
                            int end = Math.min(at16k.length, start + TYPICAL_CHUNK * 2);
                            float[] piece = java.util.Arrays.copyOfRange(at16k, start, end);
                            boolean finished = segmenter.feed(piece);
                            start = end;

                            // ДИАГНОСТИКА: первые 50 окон
                            if (vadWindowCount++ < 50) {
                                log.error("VAD #{}: rms≈{}, silenceRun={}, speechStarted={}, bufferedSeconds={}",
                                        vadWindowCount,
                                        String.format("%.4f", windowRms(piece)),
                                        silenceRun(segmenter),
                                        segmenter.speechStarted(),
                                        String.format("%.2f", segmenter.bufferedSeconds()));
                            }

                            if (finished || segmenter.bufferedSeconds() >= props.getMaxUtteranceSeconds()) {
                                String said = flushUtterance(callId, callUuid, segmenter, minSamples);
                                long recognizedAt = System.currentTimeMillis();
                                if (said != null && !said.isBlank()) {
                                    Turn turn = dialogue.onUtterance(callUuid, said, callerPhone(socket));
                                    if (turn != null && turn.reply() != null
                                            && !turn.reply().isBlank()) {
                                        callVoice.enqueue(turn.reply());
                                        log.info("Звонок #{} ход {}: этап={} действие={} "
                                                        + "ответ=«{}» озвучка поставлена, до звука {}",
                                                callId, ++turns, turn.stage(), turn.action(),
                                                brief(turn.reply()),
                                                turnLatencyMillis(recognizedAt, turn.reply()));
                                    }
                                    if (turn != null && turn.action() != Turn.Action.CONTINUE) {
                                        callVoice.awaitFinished(60);
                                        sleepQuietly(ttsProps.getCloseDelaySeconds() * 1000L);
                                        log.info("Звонок #{}: сценарий завершён ({}), закрываем соединение",
                                                callId, turn.action());
                                        return;
                                    }
                                }
                            }
                        }
                    }
                    default -> log.debug("Звонок #{}: неизвестный тип кадра 0x{}",
                            callId, Integer.toHexString(type));
                }
            }
        } catch (SocketTimeoutException e) {
            log.warn("Звонок #{}: таймаут {}", callId, e.getMessage());
        } catch (SocketException e) {
            log.info("Звонок #{}: соединение закрыто ({})", callId, e.getMessage());
        } catch (IOException e) {
            log.error("Звонок #{}: ошибка ввода-вывода: {}", callId, e.getMessage());
        } catch (RuntimeException e) {
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

    /** Номер звонящего для идентификации — из удалённого адреса сокета. */
    private static String callerPhone(Socket socket) {
        if (socket == null || !(socket.getRemoteSocketAddress() instanceof
                java.net.InetSocketAddress inet)) {
            return null;
        }
        String host = inet.getAddress() == null ? inet.getHostName()
                : inet.getAddress().getHostAddress();
        return host == null ? null : host;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        String one = text.replace('\n', ' ').trim();
        return one.length() > 80 ? one.substring(0, 80) + "…" : one;
    }

    private static String turnLatencyMillis(long recognizedAt, String reply) {
        return "~" + (System.currentTimeMillis() - recognizedAt) + " мс, ответ из "
                + CallVoice.splitSentences(reply).size() + " фр.";
    }

    private String flushUtterance(long callId, String callUuid,
                                  UtteranceSegmenter segmenter, int minSamples) {
        float[] utterance = segmenter.takeUtterance();
        if (utterance.length < minSamples) {
            log.debug("Звонок #{}: {} сэмплов (минимум {}) — тишина, не речь",
                    callId, utterance.length, minSamples);
            return "";
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

    // ------------------------------------------------------------------
    // ДИАГНОСТИКА
    // ------------------------------------------------------------------

    private static double rms(byte[] pcm) {
        if (pcm.length < 2) return 0;
        double sum = 0;
        int count = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int lo = pcm[i] & 0xFF;
            int hi = pcm[i + 1];
            int v = (hi << 8) | lo;
            sum += (double) v * v;
            count++;
        }
        return count == 0 ? 0 : Math.sqrt(sum / count) / 32768.0;
    }

    private static int[] toInt16(byte[] pcm, int n) {
        int pairs = Math.min(n, pcm.length / 2);
        int[] out = new int[pairs];
        for (int i = 0; i < pairs; i++) {
            int lo = pcm[i * 2] & 0xFF;
            int hi = pcm[i * 2 + 1];
            out[i] = (hi << 8) | lo;
        }
        return out;
    }

    private static double windowRms(float[] samples) {
        if (samples.length == 0) return 0;
        double sum = 0;
        for (float v : samples) {
            sum += (double) v * v;
        }
        return Math.sqrt(sum / samples.length);
    }

    private static int silenceRun(UtteranceSegmenter seg) {
        try {
            java.lang.reflect.Field f = UtteranceSegmenter.class.getDeclaredField("silenceRun");
            f.setAccessible(true);
            return f.getInt(seg);
        } catch (Exception e) {
            return -1;
        }
    }

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