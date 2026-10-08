package com.clinic.assistant.voice;

import com.clinic.assistant.config.VoiceProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimeWindowResolverTest {

    private final TimeWindowResolver resolver = new TimeWindowResolver(new VoiceProperties());
    private final LocalDate monday = LocalDate.of(2026, 10, 5); // понедельник

    @Test
    void tomorrowNarrowsToOneDay() {
        TimeWindow window = resolver.resolve(List.of("T_TOMORROW"), monday);

        assertThat(window.from()).isEqualTo(monday.plusDays(1));
        assertThat(window.to()).isEqualTo(monday.plusDays(1));
    }

    @Test
    void exactTimeNarrowsToHour() {
        TimeWindow window = resolver.resolve(List.of("T_TOMORROW", "T_H10_00"), monday);

        assertThat(window.earliest()).isEqualTo(LocalTime.of(10, 0));
        assertThat(window.latest()).isEqualTo(LocalTime.of(10, 0));
        assertThat(window.matches(monday.plusDays(1), LocalTime.of(10, 0))).isTrue();
        assertThat(window.matches(monday.plusDays(1), LocalTime.of(11, 0))).isFalse();
    }

    @Test
    void morningDefinesPartOfDay() {
        TimeWindow window = resolver.resolve(List.of("T_MORNING"), monday);

        assertThat(window.earliest()).isEqualTo(LocalTime.of(8, 0));
        assertThat(window.latest()).isEqualTo(LocalTime.of(12, 0));
    }

    @Test
    void weekdayFactorPicksNextOccurrence() {
        TimeWindow window = resolver.resolve(List.of("T_FRI"), monday);

        assertThat(window.from()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(window.to()).isEqualTo(LocalDate.of(2026, 10, 9));
    }

    @Test
    void unknownTimeMeansDefaultRange() {
        TimeWindow window = resolver.resolve(List.of("T_UNKNOWN"), monday);

        assertThat(window.from()).isEqualTo(monday);
        assertThat(window.to()).isAfter(monday);
        assertThat(window.earliest()).isEqualTo(LocalTime.of(0, 0));
    }
}
