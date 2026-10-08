package com.clinic.assistant.ai.frame;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceFrameTest {

    @Test
    void parsesRawFrameWithTimeBlock() {
        VoiceFrame frame = VoiceFrame.fromRaw(
                "<BOOK> <NEUROLOGIST> <TIME> T_TOMORROW T_H10_00 </TIME> <SYMPTOM_HEADACHE> <END_FRAME>");

        assertThat(frame.intent()).isEqualTo(Intent.BOOK);
        assertThat(frame.specialty()).isEqualTo("NEUROLOGIST");
        assertThat(frame.timeFactors()).containsExactly("T_TOMORROW", "T_H10_00");
        assertThat(frame.symptoms()).containsExactly("SYMPTOM_HEADACHE");
        assertThat(frame.needsClarification()).isFalse();
    }

    @Test
    void parsesShortFrameWithUnknownTime() {
        VoiceFrame frame = VoiceFrame.fromRaw("<BOOK> <GYNECOLOGIST> <T_UNKNOWN> <END_FRAME>");

        assertThat(frame.intent()).isEqualTo(Intent.BOOK);
        assertThat(frame.specialty()).isEqualTo("GYNECOLOGIST");
        assertThat(frame.timeUnknown()).isTrue();
        assertThat(frame.specialtyUnknown()).isFalse();
    }

    @Test
    void unsupportedFrameIsNotSpecial() {
        VoiceFrame frame = VoiceFrame.fromRaw("<UNSUPPORTED> <END_FRAME>");

        assertThat(frame.intent()).isEqualTo(Intent.UNSUPPORTED);
        assertThat(frame.specialtyUnknown()).isTrue();
    }

    @Test
    void buildRawRoundTripsThroughFromRaw() {
        VoiceFrame original = VoiceFrame.of(
                Intent.BOOK, "GP",
                List.of("T_TOMORROW", "T_H10_00"),
                List.of("SYMPTOM_FEVER"),
                false);

        VoiceFrame parsed = VoiceFrame.fromRaw(original.raw());

        assertThat(parsed.intent()).isEqualTo(Intent.BOOK);
        assertThat(parsed.specialty()).isEqualTo("GP");
        assertThat(parsed.timeFactors()).containsExactly("T_TOMORROW", "T_H10_00");
        assertThat(parsed.symptoms()).containsExactly("SYMPTOM_FEVER");
    }

    @Test
    void clarificationFlagIsParsed() {
        VoiceFrame frame = VoiceFrame.fromRaw(
                "<BOOK> <ENT> <T_UNKNOWN> <NEEDS_CLARIFICATION> <END_FRAME>");

        assertThat(frame.needsClarification()).isTrue();
    }
}
