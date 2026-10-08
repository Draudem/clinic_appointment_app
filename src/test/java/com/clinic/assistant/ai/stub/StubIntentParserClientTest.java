package com.clinic.assistant.ai.stub;

import com.clinic.assistant.ai.frame.Intent;
import com.clinic.assistant.ai.frame.VoiceFrame;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Заглушка парсера должна вести себя по контракту настоящей модели —
 * остальной конвейер не знает, какой именно клиент подключён.
 */
class StubIntentParserClientTest {

    private final StubIntentParserClient parser = new StubIntentParserClient();

    @Test
    void parsesBookingWithSpecialtyAndTime() {
        VoiceFrame frame = parser.parse("Записаться к неврологу завтра в 10:00");

        assertThat(frame.intent()).isEqualTo(Intent.BOOK);
        assertThat(frame.specialty()).isEqualTo("NEUROLOGIST");
        assertThat(frame.timeFactors()).contains("T_TOMORROW", "T_H10_00");
    }

    @Test
    void parsesCancelIntent() {
        VoiceFrame frame = parser.parse("Отмените мою запись к терапевту");

        assertThat(frame.intent()).isEqualTo(Intent.CANCEL);
        assertThat(frame.specialty()).isEqualTo("GP");
    }

    @Test
    void parsesRescheduleIntent() {
        VoiceFrame frame = parser.parse("хочу перенести запись к кардиологу на понедельник");

        assertThat(frame.intent()).isEqualTo(Intent.RESCHEDULE);
    }

    @Test
    void unknownTextIsUnsupported() {
        VoiceFrame frame = parser.parse("расскажи анекдот про программиста");

        assertThat(frame.intent()).isEqualTo(Intent.UNSUPPORTED);
    }

    @Test
    void wordHoursAreConverted() {
        VoiceFrame frame = parser.parse("записаться к офтальмологу в три часа дня");

        assertThat(frame.timeFactors()).contains("T_H15_00");
    }

    @Test
    void symptomsAreExtracted() {
        VoiceFrame frame = parser.parse("записаться к невропатологу, сильно болит голова");

        assertThat(frame.symptoms()).contains("SYMPTOM_HEADACHE");
    }

    @Test
    void missingTimeGivesUnknownFactor() {
        VoiceFrame frame = parser.parse("записаться к терапевту");

        assertThat(frame.timeUnknown()).isTrue();
    }
}
