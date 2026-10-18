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
 *
 * <p>Тест раньше был @Disabled «требует сложной настройки моков для MisClock».
 * На самом деле достаточно замокать MisClock.today() и MisClock.now() — что
 * здесь и сделано. Часы фиксированы, поэтому тест детерминирован.
 *
 * <p>Отдельно проверяется, что пожелание за пределами горизонта даёт пусто
 * ЯВНО (а не «случайно» через инвертированный диапазон в провайдере):
 * это поведение теперь выражено в SlotResolver.candidates.
 */
class SlotHorizonTest {

    /** Фиксированная «сегодня» — 15 октября 2026, четверг. */
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

        when(clock.today()).thenReturn(TODAY);
        when(clock.now()).thenReturn(TODAY.atTime(9, 0));   // 09:00 GMT+4

        when(repository.findByDoctorIdAndDateAndTimeAndStatus(any(), any(), any(), any()))
                .thenReturn(java.util.Optional.empty());

        // Провайдер отдаёт то, что реально запросили, — по границам from..to.
        // Так тест ловит инвертированный диапазон: при from > to список пуст.
        when(provider.bySpecialty(any(), any(), any())).thenAnswer(inv -> {
            LocalDate from = inv.getArgument(1);
            LocalDate to = inv.getArgument(2);
            List<ScheduleEntry> out = new ArrayList<>();
            for (ScheduleEntry e : mockEntries()) {
                if (!e.date().isBefore(from) && !e.date().isAfter(to)) {
                    out.add(e);
                }
            }
            return out;
        });

        resolver = new SlotResolver(provider, repository, clock);
    }

    /** По умолчанию слотов нет — каждый тест переопределяет список. */
    private List<ScheduleEntry> mockEntries() {
        return List.of();
    }

    @Test
    @DisplayName("дата далеко в будущем не даёт слот")
    void farFutureDateGivesNoSlot() {
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
        // Оба слота ВНУТРИ горизонта (16 и 23 октября при today=15.10),
        // но 16-е ближе. Тест проверяет именно сортировку «раньше — лучше».
        ScheduleEntry laterSlot = slot(2026, 10, 23, "09:00");
        ScheduleEntry nearSlot = slot(2026, 10, 16, "11:00");
        when(provider.bySpecialty(any(), any(), any())).thenReturn(List.of(laterSlot, nearSlot));

        var pref = new TimeResolver.TimePreference(null, null, null, null, null, false, "");
        var found = resolver.findSlot("Стоматолог", pref, TODAY);

        assertTrue(found.isPresent());
        assertEquals(LocalDate.of(2026, 10, 16), found.get().date(),
                "должен выбраться ближайший слот, а не первый попавшийся");
    }

    private static ScheduleEntry slot(int y, int m, int d, String time) {
        return new ScheduleEntry(3L, "Смирнов Алексей Викторович", "Стоматолог",
                LocalDate.of(y, m, d), time, 201);
    }
}