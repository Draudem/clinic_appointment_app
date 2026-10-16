package com.clinic.backend.voice;

import com.clinic.backend.schedule.ScheduleEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

/**
 * Горизонт записи — 14 дней от сегодняшнего.
 *
 * <p>Повод для теста — живой проверочный диалог. На этапе подтверждения пациент
 * назвал дату рождения («двенадцатое марта тысяча девятьсот семьдесят восьмого»),
 * и сценарий ответил «Понял. В пятницу, двенадцатого марта, в одиннадцать. Верно?» —
 * то есть предложил запись на март СЛЕДУЮЩЕГО года. Расписание открыто на
 * две недели вперёд, такой слот в принципе не может существовать.
 */
class SlotHorizonTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    @Test
    @DisplayName("дата далеко в будущем не даёт слот — предложение не подтверждается")
    void farFutureDateGivesNoSlot() {
        SlotResolver resolver = resolver(List.of(
                slot(2027, 3, 12, "11:00"),   // пятница будущего года
                slot(2026, 10, 16, "11:00")));

        LocalDate farFuture = LocalDate.of(2027, 3, 12);
        var pref = new TimeResolver.TimePreference(farFuture, null, null, null, null, false, "");
        assertTrue(resolver.findSlot("Стоматолог", pref, TODAY).isEmpty(),
                "запись дальше 14 дней не предлагается");
    }

    @Test
    @DisplayName("дата внутри горизонта находится")
    void nearDateFound() {
        SlotResolver resolver = resolver(List.of(
                slot(2026, 10, 16, "11:00"),
                slot(2026, 10, 20, "10:30")));

        LocalDate target = LocalDate.of(2026, 10, 20);
        var pref = new TimeResolver.TimePreference(target, null, null, null, null, false, "");
        var found = resolver.findSlot("Терапевт", pref, TODAY);
        assertTrue(found.isPresent(), "дата внутри двух недель доступна");
        assertEquals(target, found.get().date());
    }

    @Test
    @DisplayName("без пожеланий берётся ближайшее из горизонта")
    void nearestWithoutPreference() {
        SlotResolver resolver = resolver(List.of(
                slot(2026, 10, 23, "09:00"),   // за 14 дней — вне горизонта
                slot(2026, 10, 16, "11:00")));

        var pref = new TimeResolver.TimePreference(null, null, null, null, null, false, "");
        var found = resolver.findSlot("Стоматолог", pref, TODAY);
        assertTrue(found.isPresent());
        assertEquals(LocalDate.of(2026, 10, 16), found.get().date(),
                "ближайший, а не тот, что за пределами горизонта");
    }

    private static ScheduleEntry slot(int y, int m, int d, String time) {
        return new ScheduleEntry(3L, "Смирнов Алексей Викторович", "Стоматолог",
                LocalDate.of(y, m, d), time, 201);
    }

    private static SlotResolver resolver(List<ScheduleEntry> entries) {
        List<ScheduleEntry> all = new ArrayList<>(entries);
        com.clinic.backend.schedule.ScheduleProvider provider =
                org.mockito.Mockito.mock(com.clinic.backend.schedule.ScheduleProvider.class);
        org.mockito.Mockito.when(provider.bySpecialty(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    java.time.LocalDate from = inv.getArgument(1);
                    java.time.LocalDate to = inv.getArgument(2);
                    List<ScheduleEntry> out = new ArrayList<>();
                    for (ScheduleEntry e : all) {
                        if (!e.date().isBefore(from) && !e.date().isAfter(to)) {
                            out.add(e);
                        }
                    }
                    return out;
                });
        com.clinic.backend.appointment.AppointmentRepository repo =
                org.mockito.Mockito.mock(com.clinic.backend.appointment.AppointmentRepository.class);
        org.mockito.Mockito.when(repo.findByDoctorIdAndDateAndTimeAndStatus(
                        any(), any(), any(), any()))
                .thenReturn(java.util.Optional.empty());
        return new SlotResolver(provider, repo);
    }
}