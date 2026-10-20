package com.clinic.backend.stt;

import com.clinic.backend.voice.AsteriskCaller;
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

@Component
@ConditionalOnProperty(prefix = "app.stt", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AsteriskAudioSocketServer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AsteriskAudioSocketServer.class);

    private static final int TYPE_TERMINATE = 0x00;
    private static final int TYPE_UUID = 0x01;
    private static final int TYPE_AUDIO = 0x10;

    private static final int TYPICAL_CHUNK = 320;
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

    private void handleCall(Socket socket) {
        long callId = callCounter.incrementAndGet();
        String remote = socket.getRemoteSocketAddress() == null
                ? "?" : socket.getRemoteSocketAddress().toString();
        log.info("Звонок #{} подключён ({})", callId, remote);

        PcmResampler resampler = new PcmResampler();
        UtteranceSegmenter segmenter = new UtteranceSegmenter(
                props.getSilenceRmsThreshold(), (int) (16000 * 0.03));
        segmenter.silenceSeconds = (float) props.getSilenceSeconds();
        int minSamples = (int) (16000 * props.getMinUtteranceSeconds());
        String callUuid = "call-" + callId;
        CallVoice voice = null;
        int turns = 0;

        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            voice = new CallVoice(out, synthesizer);
            final CallVoice callVoice = voice;

            while (running) {
                int type = in.read();
                if (type < 0) break;
                int length = in.readUnsignedShort();
                if (length < 0 || length > 1_000_000) break;
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

                            Long appId = AsteriskCaller.ACTIVE_REMINDER_CALLS.get(callUuid);
                            if (appId != null) {
                                // ЭТО ИСХОДЯЩИЙ ЗВОНОК-НАПОМИНАНИЕ (С-10)
                                log.info("Звонок #{}: UUID {} (С-10, запись #{})", callId, callUuid, appId);
                                dialogue.startReminderCall(callUuid, appId);
                                AsteriskCaller.ACTIVE_REMINDER_CALLS.remove(callUuid);

                                // Озвучиваем приветствие напоминания
                                String reminderGreeting = "Здравствуйте! Это виртуальный помощник поликлиники номер семь. Напоминаю о вашей записи на завтра. Вы придёте?";
                                callVoice.enqueue(reminderGreeting);
                                log.info("Звонок #{}: озвучено приветствие напоминания (С-10)", callId);
                            } else {
                                // ОБЫЧНЫЙ ВХОДЯЩИЙ ЗВОНОК
                                log.info("Звонок #{}: UUID {} (входящий)", callId, callUuid);
                                dialogue.onCallStarted(callUuid, callerPhone(socket));
                                callVoice.enqueue(dialogue.greeting());
                                log.info("Звонок #{}: озвучено приветствие регистратуры", callId);
                            }
                        }
                    }
                    case TYPE_AUDIO -> {
                        float[] at8k = PcmResampler.bytesToFloat(payload, 0, payload.length);
                        float[] at16k = resampler.resample(at8k);

                        boolean nowSpeaking = callVoice.isSpeaking();
                        if (nowSpeaking) {
                            continue;
                        }

                        int start = 0;
                        while (start < at16k.length) {
                            int end = Math.min(at16k.length, start + TYPICAL_CHUNK * 2);
                            float[] piece = java.util.Arrays.copyOfRange(at16k, start, end);
                            boolean finished = segmenter.feed(piece);
                            start = end;

                            if (finished || segmenter.bufferedSeconds() >= props.getMaxUtteranceSeconds()) {
                                String said = flushUtterance(callId, callUuid, segmenter, minSamples);
                                if (said != null && !said.isBlank()) {
                                    Turn turn = dialogue.onUtterance(callUuid, said, callerPhone(socket));
                                    if (turn != null && turn.reply() != null && !turn.reply().isBlank()) {
                                        callVoice.enqueue(turn.reply());
                                        log.info("Звонок #{} ход {}: этап={} действие={} ответ=«{}»",
                                                callId, ++turns, turn.stage(), turn.action(), brief(turn.reply()));
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
            log.error("Звонок #{}: непредвиденная ошибка, переводим на оператора", callId, e);
            try {
                if (voice != null) {
                    voice.enqueue("Извините, произошла ошибка. Соединяю с сотрудником.");
                    voice.awaitFinished(10);
                }
            } catch (RuntimeException ignored) {}
        } finally {
            if (voice != null) voice.close();
            try { socket.close(); } catch (IOException ignored) {}
            log.info("Звонок #{} завершён, распознано фраз: {}", callId, transcriptStore.countFor(callUuid));
        }
    }

    private static String callerPhone(Socket socket) {
        if (socket == null || !(socket.getRemoteSocketAddress() instanceof java.net.InetSocketAddress inet)) {
            return null;
        }
        String host = inet.getAddress() == null ? inet.getHostName() : inet.getAddress().getHostAddress();
        return host == null ? null : host;
    }

    private static void sleepQuietly(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String brief(String text) {
        if (text == null) return "";
        String one = text.replace('\n', ' ').trim();
        return one.length() > 80 ? one.substring(0, 80) + "…" : one;
    }

    private String flushUtterance(long callId, String callUuid, UtteranceSegmenter segmenter, int minSamples) {
        float[] utterance = segmenter.takeUtterance();
        if (utterance.length < minSamples) return "";
        String text = recognizer.transcribe(utterance);
        if (text == null || text.isBlank()) {
            transcriptStore.add(callUuid, "", (utterance.length / 16000.0f));
            return "";
        }
        log.info("Звонок #{} РАСПОЗНАНО: «{}»", callId, text);
        transcriptStore.add(callUuid, text, utterance.length / 16000.0f);
        return text;
    }

    private static long bytesToLong(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 0xFFL);
        return v;
    }

    public boolean isListening() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    @PreDestroy
    void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        workers.shutdownNow();
    }

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
                if (piper.available()) return piper;
            }
            return new SilentReplySynthesizer();
        }

        @Bean
        TranscriptStore transcriptStore() {
            return new TranscriptStore();
        }
    }
}