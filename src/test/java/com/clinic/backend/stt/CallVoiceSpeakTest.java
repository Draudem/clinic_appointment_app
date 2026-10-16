package com.clinic.backend.stt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Озвучка ответа: поток должен выживать и писать аудио.
 *
 * <p>Повод для теста — реальный сбой. В строке лога «Озвучено … с за … мс»
 * стояло {@code String.format("%.1f", spoken / AUDIO_RATE)}, где {@code spoken}
 * — {@code long}. {@code long / int} даёт {@code long}, а {@code %.1f} требует
 * дробное число, и String.format бросал IllegalFormatConversionException
 * ПОСЛЕ того, как аудио уже ушло в сокет. Исключение не ловилось ни в speak(),
 * ни в run(), поток call-voice умирал — и голос пропадал навсегда. При этом
 * {@code /api/stt/status} отвечал «всё хорошо», а сбоя в логах приложения не
 * было вовсе: исключение печатала сама JVM в stderr.
 *
 * <p>Здесь поток закрыт заглушкой синтеза: {@code sleepFrame} ждёт 20 мс на
 * кадр, поэтому проверяем на короткой фразе.
 */
class CallVoiceSpeakTest {

    /** Заглушка синтеза: тихий ровный сигнал заданной длительности. */
    private static ReplySynthesizer stub(float seconds) {
        return new ReplySynthesizer() {
            @Override
            public float[] synthesize(String text) {
                return new float[(int) (sampleRate() * seconds)];
            }

            @Override
            public int sampleRate() {
                return 22050;
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String name() {
                return "stub";
            }
        };
    }

    @Test
    @DisplayName("фраза уходит в поток целиком, поток озвучки жив")
    void speaksAndStaysAlive() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        CallVoice voice = new CallVoice(sink, stub(0.2f));
        try {
            voice.enqueue("Здравствуйте. Чем могу помочь?");
            assertTrue(voice.awaitFinished(20), "фраза должна дозвучать");
            assertTrue(voice.isAlive(), "поток озвучки обязан остаться живым");
            assertEquals(0, voice.pendingCount());
        } finally {
            voice.close();
        }
        // каждый кадр: тип 0x10 + длина big endian + PCM
        assertTrue(sink.size() > 3, "в поток должно уйти хоть что-то: " + sink.size());
        assertEquals(0x10, sink.toByteArray()[0], "первый байт — тип аудио");
    }

    @Test
    @DisplayName("сбой синтеза на одной фразе не убивает поток — следующая озвучивается")
    void survivesSynthesisFailure() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        int[] call = {0};
        ReplySynthesizer failing = new ReplySynthesizer() {
            @Override
            public float[] synthesize(String text) {
                if (call[0]++ == 0) {
                    throw new IllegalStateException("движок синтеза сдох");
                }
                return new float[22050];
            }

            @Override
            public int sampleRate() {
                return 22050;
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String name() {
                return "failing-stub";
            }
        };

        CallVoice voice = new CallVoice(sink, failing);
        try {
            voice.enqueue("Первая фраза. Она сорвётся.");
            Thread.sleep(300);
            assertTrue(voice.isAlive(),
                    "поток не должен умирать из-за одной плохой фразы — "
                            + "иначе голос пропадает до конца звонка");

            voice.enqueue("Вторая фраза.");
            assertTrue(voice.awaitFinished(20), "вторая фраза должна дозвучать");
            assertTrue(sink.size() > 0, "вторая фраза реально ушла в поток");
        } finally {
            voice.close();
        }
        assertTrue(CallVoice.spokenFailed() > 0, "сбой обязан быть посчитан");
    }

    @Test
    @DisplayName("обрыв записи завершает озвучку штатно, без падения потока")
    void brokenPipeStopsVoice() {
        ReplySynthesizer synth = stub(0.3f);
        CallVoice voice = new CallVoice(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("соединение разорвано");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("соединение разорвано");
            }
        }, synth);
        try {
            voice.enqueue("Фраза в никуда.");
            assertFalse(voice.awaitFinished(10), "при обрыве ждать дозвучивания бессмысленно");
            assertFalse(voice.failure() == null, "обрыв должен быть виден в failure()");
        } finally {
            voice.close();
        }
    }
}