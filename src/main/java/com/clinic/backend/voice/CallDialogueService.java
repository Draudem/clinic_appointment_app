package com.clinic.backend.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Мост между телефонным каналом и сценарием.
 *
 * ЗАЧЕМ ОТДЕЛЬНЫЙ КЛАСС. Раньше телефонный путь не зависел от диалога вообще:
 * на ПЕРВУЮ распознанную фразу он отвечал «Спасибо, записываю» и вешал трубку —
 * что бы пациент ни сказал. Теперь телефон лишь переводит речь в текст и
 * озвучивает то, что вернул сценарий. Логика записи живёт в одном месте —
 * в {@link BookingFlow} — поэтому HTTP и телефон не могут разойтись.
 *
 * Сессия сценария привязана к ключу звонка (UUID от Asterisk) и лежит в БД,
 * поэтому она переживает переподключение и видна в /api/voice/sessions.
 *
 * Тишина в трубке (R-17): 5 секунд — «Вы меня слышите?», ещё 5 —
 * «Вас не слышно. Перезвоните, пожалуйста» и конец разговора.
 */
@Service
public class CallDialogueService {

    private static final Logger log = LoggerFactory.getLogger(CallDialogueService.class);

    private final DialogueManager dialogue;

    public CallDialogueService(DialogueManager dialogue) {
        this.dialogue = dialogue;
    }

    /** Приветствие по регламенту 3.3 — с обязательным упоминанием записи разговора. */
    public String greeting() {
        return "Здравствуйте! Регистратура поликлиники номер семь, виртуальный помощник. "
                + "Разговор записывается. Чем могу помочь?";
    }

    /** Звонок начался — состояние прежнего разговора сбрасывается. */
    public void onCallStarted(String callKey, String ani) {
        dialogue.startCall(callKey, ani);
    }

    /**
     * Одна распознанная реплика пациента.
     *
     * @param callKey идентификатор звонка (UUID от Asterisk)
     * @param text    распознанный текст
     * @param ani     номер звонящего — используется как дополнительный признак
     */
    public Turn onUtterance(String callKey, String text, String ani) {
        Turn turn = dialogue.handleByCall(callKey, ani, text);
        if (turn.action() == Turn.Action.TRANSFER) {
            log.info("Звонок {}: ПЕРЕВОД (причина: {}) этап={} реплика=«{}» ответ=«{}»",
                    callKey, turn.transferReason(), turn.stage(),
                    abbreviate(text), abbreviate(turn.reply()));
        } else {
            log.info("Звонок {}: этап={} действие={} уверенность={} реплика=«{}» ответ=«{}»",
                    callKey, turn.stage(), turn.action(), turn.confidence(),
                    abbreviate(text), abbreviate(turn.reply()));
        }
        return turn;
    }

    /** Пациент говорит — тишина сброшена. */
    public Turn onSpeech(String callKey) {
        return dialogue.touchCall(callKey);
    }

    /** Тишина в трубке — реакция по R-17. */
    public Turn onSilence(String callKey, Duration silentFor) {
        long seconds = silentFor.getSeconds();
        if (seconds < 5) {
            return Turn.continueWith(null, "WAITING", List.of(), 0);
        }
        if (seconds < 10) {
            return Turn.continueWith("Вы меня слышите?", "WAITING", List.of(), 0);
        }
        return Turn.hangup("Вас не слышно. Перезвоните, пожалуйста.", "CLOSED", "DROPPED", 0);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}