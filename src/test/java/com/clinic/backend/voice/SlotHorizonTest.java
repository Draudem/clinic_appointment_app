package com.clinic.backend.voice;

import com.clinic.backend.appointment.AppointmentRepository;
import com.clinic.backend.mis.MisClock;
import com.clinic.backend.schedule.ScheduleEntry;
import com.clinic.backend.schedule.ScheduleProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Горизонт записи — 14 дней от сегодняшнего (по GMT+4).
 */
class SlotHorizonTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    private SlotResolver resolver;
    private ScheduleProvider provider;
    private AppointmentRepository repository;
    private MisClock clock;

    @BeforeEach
    void setUp() {
        provider = mock(ScheduleProvider.class);
        repository = mock(AppointmentRepository.class);
        clock = mock(MisClock.class);

        // Настраиваем мок часов
        when(clock.today()).thenReturn(TODAY);
        when(clock.now()).thenReturn(LocalDateTime.now());

        // Настраиваем мок репозитория (слоты всегда свободны)
        when(repository.findByDoctorIdAndDateAndTimeAndStatus(any(), any(), any(), any()))
                .thenReturn(java.util.Optional.empty());

        // Настраиваем провайдера (будет возвращать то, что мы передадим в тесте)
        when(provider.bySpecialty(any(), any(), any())).thenAnswer(inv -> {
            java.time.LocalDate from = inv.getArgument(1);
            java.time.LocalDate to = inv.getArgument(2);
            List<ScheduleEntry> all = mockEntries(); // будем заполнять в самих тестах
            List<ScheduleEntry> out = new ArrayList<>();
            for (ScheduleEntry e : all) {
                if (!e.date().isBefore(from) && !e.date().isAfter(to)) {
                    out.add(e);
                }
            }
            return out;
        });

        resolver = new SlotResolver(provider, repository, clock);
    }

    // Вспомогательный метод для генерации списка слотов
    private List<ScheduleEntry> mockEntries() {
        return List.of(); // по умолчанию пусто, переопределяем в тестах
    }

    @Test
    @DisplayName("дата далеко в будущем не даёт слот")
    void farFutureDateGivesNoSlot() {
        // Настраиваем конкретный ответ для этого теста
        ScheduleEntry farFutureSlot = slot(2027, 3, 12, "11:00");
        when(provider.bySpecialty(any(), any(), any())).thenReturn(List.of(farFutureSlot));

        LocalDate farFuture = LocalDate.of(2027, 3, 12);
        var pref = new TimeResolver.TimePreference(farFuture, null, null, null, null, false, "");

        assertTrue(resolver.findSlot("Стоматолог", pref, TODAY).isEmpty(),
                "запись дальше 14 дней не предлагается");
    }

    @Test
    @DisplayName("дата внутри горизонта находится")
    void nearDateFound() {
        ScheduleEntry nearSlot = slot(2026, 10, 20, "10:30");
        when(provider.bySpecialty(any(), any(), any())).thenReturn(List.of(nearSlot));

        LocalDate target = LocalDate.of(2026, 10, 20);
        var pref = new TimeResolver.TimePreference(target, null, null, null, null, false, "");

        var found = resolver.findSlot("Терапевт", pref, TODAY);
        assertTrue(found.isPresent(), "дата внутри двух недель доступна");
        assertEquals(target, found.get().date());
    }

    @Test
    @DisplayName("без пожеланий берётся ближайшее из горизонта")
    void nearestWithoutPreference() {
        ScheduleEntry farSlot = slot(2026, 10, 23, "09:00"); // за пределами горизонта
        ScheduleEntry nearSlot = slot(2026, 10, 16, "11:00"); // внутри
        when(provider.bySpecialty(any(), any(), any())).thenReturn(List.of(farSlot, nearSlot));

        var pref = new TimeResolver.TimePreference(null, null, null, null, null, false, "");
        var found = resolver.findSlot("Стоматолог", pref, TODAY);

        assertTrue(found.isPresent());
        assertEquals(LocalDate.of(2026, 10, 16), found.get().date(),
                "должен выбраться ближайший, а не тот, что за пределами горизонта");
    }

    private static ScheduleEntry slot(int y, int m, int d, String time) {
        return new ScheduleEntry(3L, "Смирнов Алексей Викторович", "Стоматолог",
                LocalDate.of(y, m, d), time, 201);
    }
}