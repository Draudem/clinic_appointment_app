package com.clinic.backend.voice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Правило подтверждения: решает ПОСЛЕДНЕЕ сказанное слово.
 *
 * <p>Повод для теста — два боевых звонка, где логика работала неверно:
 * пациент говорил «нет», а его записывали. Старое правило сначала искало любое
 * слово из NO, затем любое из YES, а короткое слово требовало, чтобы вся
 * реплика была ровно этим словом. В реплике «…восемь тридцать верно нет»
 * выигрывал «верно» — он длинный и искался подстрокой, — и отказ терялся.
 */
class PolicyRulesAnswerTest {

    @Test
    @DisplayName("отказ, сказанный после согласия, отменяет согласие")
    void lastWordWins() {
        assertEquals(PolicyRules.Answer.NO, PolicyRules.answer("да... да .... нет"));
        assertEquals(PolicyRules.Answer.YES, PolicyRules.answer("да.... нет.... да"));
    }

    @Test
    @DisplayName("звонок #25: «верно нет» — это отказ, а не согласие")
    void realCallRefusal() {
        assertEquals(PolicyRules.Answer.NO,
                PolicyRules.answer("понял в пятницу шестнадцатого октября в девять верно нет"));
    }

    @Test
    @DisplayName("звонок #21: «да» в конце длинной фразы — согласие")
    void realCallConsent() {
        assertEquals(PolicyRules.Answer.YES,
                PolicyRules.answer("спасибо игорь николаевич в понедельник "
                        + "девятнадцатого октября в восемь тридцать поятно да"));
        assertEquals(PolicyRules.Answer.NO,
                PolicyRules.answer("понедельни девятнадцатог октябрявоветд или нт нет"));
    }

    @Test
    @DisplayName("короткое слово ищется целиком, а не внутри другого")
    void shortWordsAreWhole() {
        // «не» внутри «понедельник» — не отказ. Это главная ловушка подстрочного
        // поиска: любая фраза с днём недели стала бы отказом от записи.
        assertEquals(PolicyRules.Answer.UNKNOWN,
                PolicyRules.answer("хочу в понедельник"));
        assertEquals(PolicyRules.Answer.UNKNOWN,
                PolicyRules.answer("хочу в понедельник к стоматологу"));
        // «рада» содержит «да», но согласие это не выражает
        assertEquals(PolicyRules.Answer.UNKNOWN, PolicyRules.answer("я рада"));
    }

    @Test
    @DisplayName("обычные ответы без хвоста работают как раньше")
    void plainAnswers() {
        assertEquals(PolicyRules.Answer.YES, PolicyRules.answer("да"));
        assertEquals(PolicyRules.Answer.NO, PolicyRules.answer("нет"));
        assertEquals(PolicyRules.Answer.YES, PolicyRules.answer("ага"));
        assertEquals(PolicyRules.Answer.NO, PolicyRules.answer("отмена"));
        assertEquals(PolicyRules.Answer.UNKNOWN, PolicyRules.answer("а когда приём"));
        assertEquals(PolicyRules.Answer.UNKNOWN, PolicyRules.answer(""));
    }
}