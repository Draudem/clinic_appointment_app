package com.clinic.backend.stt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбивка фразы на куски для потоковой озвучки.
 *
 * <p>Зачем: раньше {@code CallVoice.speak()} синтезировал всю фразу и только
 * потом отправлял аудио. Замер по логу: на приветствии 7,8 с первый звук
 * уходил через 0,7–1,4 с после постановки в очередь. Вместе с ожиданием
 * распознавания это давало 5–6 с тишины до первого слова ответа.
 */
class CallVoiceSplitTest {

    @Test
    @DisplayName("приветствие режется на предложения — регламентная фраза и вопрос")
    void greetingSplits() {
        List<String> parts = CallVoice.splitSentences(
                "Здравствуйте! Регистратура поликлиники номер семь, виртуальный помощник. "
                        + "Разговор записывается. Чем могу помочь?");
        assertEquals(4, parts.size(), parts.toString());
        assertEquals("Здравствуйте!", parts.get(0));
        assertTrue(parts.get(3).startsWith("Чем могу"));
    }

    @Test
    @DisplayName("фраза без точек остаётся цельной, если она короткая")
    void shortPhraseStaysWhole() {
        assertEquals(List.of("В пятницу, в девять."),
                CallVoice.splitSentences("В пятницу, в девять."));
    }

    @Test
    @DisplayName("точка внутри числа не считается концом предложения")
    void decimalPointIsNotBoundary() {
        List<String> parts = CallVoice.splitSentences("Приём в 9.30. Записать вас?");
        assertEquals(2, parts.size(), parts.toString());
        assertTrue(parts.get(0).contains("9.30"), parts.toString());
    }

    @Test
    @DisplayName("длинная фраза без знаков режется по словам")
    void longPhraseSplitsByWords() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("слово").append(i).append(' ');
        }
        List<String> parts = CallVoice.splitSentences(sb.toString());
        assertTrue(parts.size() > 1, "фраза из 60 слов должна быть порезана");
        for (String part : parts) {
            assertFalse(part.isBlank());
        }
    }

    @Test
    @DisplayName("пустая и пустая-в-скобках фраза не ломает разбор")
    void degenerateInput() {
        assertTrue(CallVoice.splitSentences(null).isEmpty());
        assertTrue(CallVoice.splitSentences("   ").isEmpty());
    }
}