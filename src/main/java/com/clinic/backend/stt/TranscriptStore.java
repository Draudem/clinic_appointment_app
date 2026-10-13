package com.clinic.backend.stt;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Хранилище распознанных фраз — чтобы напарник (и мобильное приложение) могли
 * забрать текст по HTTP во время теста.
 *
 * AudioSocket не умеет передавать текст: он carries только аудио. Поэтому «вернуть
 * текст» можно двумя способами — озвучить его (TTS, ещё не подключён) или отдать
 * по HTTP. На первом тесте работает второй.
 *
 * Данные в памяти и намеренно без срока жизни: это диагностика, а не журнал
 * аудиозаписей. На каждый звонок держим последние фразы.
 */
@Component
public class TranscriptStore {

    private static final int MAX_PER_CALL = 20;
    private static final int MAX_CALLS = 50;

    public record Utterance(String callId, String text, float seconds, LocalDateTime at) {
    }

    private final Deque<Utterance> all = new ArrayDeque<>();
    private final Deque<Utterance> last = new ArrayDeque<>();

    public synchronized void add(String callId, String text, float seconds) {
        Utterance u = new Utterance(callId, text, seconds, LocalDateTime.now());
        last.addFirst(u);
        while (last.size() > MAX_PER_CALL) {
            last.removeLast();
        }
        all.addFirst(u);
        while (all.size() > MAX_CALLS) {
            all.removeLast();
        }
    }

    /** Последняя распознанная фраза (пусто, если ещё ничего не распознано). */
    public synchronized Utterance last() {
        return last.peekFirst();
    }

    /** Все фразы конкретного звонка, свежие первыми. */
    public synchronized List<Utterance> forCall(String callId) {
        List<Utterance> result = new ArrayList<>();
        for (Utterance u : last) {
            if (u.callId().equals(callId)) {
                result.add(u);
            }
        }
        return result;
    }

    /** Все фразы по всем звонкам, свежие первыми. */
    public synchronized List<Utterance> recent() {
        return new ArrayList<>(last);
    }

    public synchronized int countFor(String callId) {
        int n = 0;
        for (Utterance u : last) {
            if (u.callId().equals(callId)) {
                n++;
            }
        }
        return n;
    }
}