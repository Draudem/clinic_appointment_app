package com.clinic.backend.voice;

import com.clinic.backend.appointment.AppointmentService;
import com.clinic.backend.doctor.DoctorRepository;
import com.clinic.backend.identity.PatientIdentity;
import com.clinic.backend.identity.PatientIdentityResolver;
import com.clinic.backend.schedule.ScheduleEntry;
import com.clinic.backend.schedule.ScheduleProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Сценарий С-01 «Запись на приём».
 *
 * Проверяем ровно то, что требует регламент РГ-ТЕЛ-01: идентификация до
 * записи, подтверждение вслух, предложение не более трёх вариантов,
 * перенаправление на перенос при дубликате и немедленный перевод на человека
 * по красным флагам и просьбе.
 */
class BookingFlowTest {

    private BookingFlow flow;
    private AppointmentService appointments;
    private PatientIdentityResolver identity;
    private SlotResolver slots;
    private DialogueSession session;

    private static final LocalDate TODAY = LocalDate.now();
    private static final PatientIdentity SMITH =
            new PatientIdentity(1L, "smirnova", "Смирнова", "Ольга", "Викторовна",
                    LocalDate.of(1978, 3, 12), "+79001234567", "test");

    @BeforeEach
    void setUp() {
        appointments = mock(AppointmentService.class);
        identity = mock(PatientIdentityResolver.class);
        slots = mock(SlotResolver.class);
        CommandParser parser = mock(CommandParser.class);

        // по умолчанию NLU понимает только специальность из первого сообщения
        when(parser.parse(anyString())).thenAnswer(inv -> {
            String t = ((String) inv.getArgument(0)).toLowerCase();
            String specialty = null;
            if (t.contains("стоматолог") || t.contains("дантист") || t.contains("зуб")) {
                specialty = "Стоматолог";
            } else if (t.contains("лор") || t.contains("ух")) {
                specialty = "ЛОР";
            } else if (t.contains("терапевт")) {
                specialty = "Терапевт";
            }
            return new ParsedCommand("BOOK", specialty, List.of(), List.of(),
                    false, specialty == null, specialty == null, "", "test");
        });
        when(slots.specialtyStaffed(anyString())).thenReturn(true);
        when(slots.availableSpecialties()).thenReturn(List.of("Терапевт", "Стоматолог", "ЛОР"));
        when(slots.offerSlots(anyString(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(testSlots());
        when(slots.findSlot(anyString(), any(), any()))
                .thenReturn(Optional.of(new SlotResolver.Slot(testSlots().get(0).entry())));

        when(appointments.listFor(anyString(), any(Boolean.class))).thenReturn(List.of());
        when(appointments.book(anyString(), any(), any(), anyString()))
                .thenReturn(Map.of("id", 99L, "doctorName", "Смирнов Алексей Викторович",
                        "specialty", "Стоматолог", "date", TODAY.plusDays(1).toString(),
                        "time", "11:00", "status", "CONFIRMED"));

        when(identity.findByFioAndBirthDate(any(), any()))
                .thenReturn(Optional.of(SMITH));

        flow = new BookingFlow(parser, slots, appointments, identity,
                mock(DialogueSessionRepository.class), new ScheduleQueryService(
                        mock(DoctorRepository.class), mock(ScheduleProvider.class)));
        session = new DialogueSession("");
    }

    private List<SlotResolver.Slot> testSlots() {
        List<SlotResolver.Slot> list = new ArrayList<>();
        list.add(new SlotResolver.Slot(new ScheduleEntry(3L, "Смирнов Алексей Викторович",
                "Стоматолог", TODAY.plusDays(1), "09:00", 201)));
        list.add(new SlotResolver.Slot(new ScheduleEntry(3L, "Смирнов Алексей Викторович",
                "Стоматолог", TODAY.plusDays(2), "11:00", 201)));
        list.add(new SlotResolver.Slot(new ScheduleEntry(3L, "Смирнов Алексей Викторович",
                "Стоматолог", TODAY.plusDays(3), "15:00", 201)));
        return list;
    }

    private Turn say(String text) {
        return flow.handle(session, PatientIdentity.unknown(), text);
    }

    private Turn say(String text, PatientIdentity who) {
        return flow.handle(session, who, text);
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("С-01: полный цикл записи")
    class FullCycle {

        @Test
        @DisplayName("запись создаётся только после называния ФИО, даты рождения и подтверждения")
        void bookEndToEnd() {
            Turn t1 = say("хочу записаться к стоматологу");
            assertEquals("IDENTITY", t1.stage(), "без идентификации запись не начинаем");
            assertTrue(t1.reply().contains("фамилию"));

            Turn t2 = say("Смирнова Ольга Викторовна, двенадцатое марта семьдесят восьмого");
            assertEquals("OFFER", t2.stage(), "после идентификации предлагаем слоты");
            assertFalse(t2.options().isEmpty());
            assertTrue(t2.options().size() <= 3, "регламент 3.2: не более трёх вариантов");

            Turn t3 = say("второй");
            assertEquals("PENDING_CONFIRMATION", t3.stage());
            assertTrue(t3.reply().contains("Записываю"), "подтверждаем вслух: " + t3.reply());

            Turn t4 = say("да");
            assertEquals(Turn.Action.HANGUP, t4.action());
            assertEquals("BOOKED", t4.resultCode());
            assertTrue(t4.reply().contains("вы записаны"));
            assertEquals("CONFIRMED", session.getStage());
        }

        @Test
        @DisplayName("ответ «нет» не создаёт запись")
        void refusalDoesNotBook() {
            say("хочу записаться к стоматологу");
            say("Смирнова Ольга Викторовна, 12.03.1978");
            Turn t = say("второй");
            Turn after = say("нет");
            assertEquals(Turn.Action.CONTINUE, after.action());
            assertEquals("OFFER", after.stage());
            assertEquals(Turn.Action.CONTINUE, t.action());
        }
    }

    @Nested
    @DisplayName("Идентификация пациента (регламент 3.5)")
    class Identity {

        @Test
        @DisplayName("без идентификации запись невозможна")
        void noIdentityNoBooking() {
            Turn t = say("хочу записаться к стоматологу");
            assertEquals("IDENTITY", t.stage());
            assertNotNull(t.reply());
        }

        @Test
        @DisplayName("не найден в базе — просим проверить, не переводим сразу")
        void notFound() {
            when(identity.findByFioAndBirthDate(any(), any())).thenReturn(Optional.empty());
            say("хочу записаться к стоматологу");
            Turn t = say("Смирнова Ольга Викторовна, двенадцатое марта семьдесят восьмого");
            assertEquals("IDENTITY", t.stage());
            assertEquals(Turn.Action.CONTINUE, t.action());
        }

        @Test
        @DisplayName("ФИО и дата рождения разбираются из обычной фразы")
        void parseSpokenIdentity() {
            var d = com.clinic.backend.identity.PatientNameParser
                    .parse("Смирнова Ольга Викторовна, двенадцатое марта семьдесят восьмого");
            assertEquals("Смирнова Ольга Викторовна", d.fio());
            assertEquals(LocalDate.of(1978, 3, 12), d.birthDate());

            var e = com.clinic.backend.identity.PatientNameParser
                    .parse("Петров Игорь Николаевич, 05.05.1962");
            assertEquals("Петров Игорь Николаевич", e.fio());
            assertEquals(LocalDate.of(1962, 5, 5), e.birthDate());

            var f = com.clinic.backend.identity.PatientNameParser
                    .parse("Соколов, двенадцатое три семьдесят восемь");
            assertEquals(LocalDate.of(1978, 3, 12), f.birthDate());
        }
    }

    @Nested
    @DisplayName("Перенаправление на С-03 при дубликате")
    class Duplicate {

        private void withExistingBooking() {
            when(appointments.listFor(anyString(), any(Boolean.class))).thenReturn(List.of(
                    Map.of("id", 7L, "doctorName", "Смирнов Алексей Викторович",
                            "specialty", "Стоматолог",
                            "date", TODAY.plusDays(2).toString(), "time", "11:00",
                            "status", "CONFIRMED")));
        }

        @Test
        @DisplayName("ассистент замечает свою запись и предлагает перенести")
        void offersReschedule() {
            withExistingBooking();
            say("хочу записаться к стоматологу");
            Turn t = say("Смирнова Ольга Викторовна, двенадцатое марта семьдесят восьмого");
            assertEquals("DUP", t.stage());
            assertTrue(t.reply().contains("уже есть запись"));
            assertTrue(t.reply().contains("Хотите перенести"), t.reply());
            assertEquals(7L, session.getPendingAppointmentId());
        }

        @Test
        @DisplayName("«да» на перенос ведёт в сценарий переноса и создаёт новую запись")
        void rescheduleCreatesNewBooking() {
            withExistingBooking();
            when(appointments.book(anyString(), any(), any(), anyString()))
                    .thenReturn(Map.of("id", 100L, "doctorName", "Смирнов Алексей Викторович",
                            "specialty", "Стоматолог", "date", TODAY.plusDays(5).toString(),
                            "time", "15:00", "status", "CONFIRMED"));
            say("хочу записаться к стоматологу");
            say("Смирнова Ольга Викторовна, двенадцатое марта семьдесят восьмого");
            Turn offered = say("да");
            assertEquals("RESCHEDULE", offered.stage(), "перенос предлагает новые слоты");

            Turn picked = say("третий");
            assertEquals("PENDING_CONFIRMATION", picked.stage());
            assertTrue(picked.reply().toLowerCase().contains("переношу"), picked.reply());

            Turn done = say("да");
            assertEquals("RESCHEDULED", done.resultCode());
            assertEquals(Turn.Action.HANGUP, done.action());
        }
    }

    @Nested
    @DisplayName("Сквозные правила регламента (раздел 7)")
    class Policy {

        @Test
        @DisplayName("красный флаг А прерывает сценарий (R-01)")
        void redFlagInterrupts() {
            say("хочу записаться к стоматологу");
            Turn t = say("ой, у меня сильно давит в груди");
            assertEquals(Turn.Action.TRANSFER, t.action());
            assertEquals("R-01", t.rule());
            assertEquals("EMERGENCY", t.resultCode());
            assertTrue(t.reply().contains("сто три"), t.reply());
        }

        @Test
        @DisplayName("красный флаг Б ведёт к регистратору (R-11)")
        void redFlagB() {
            Turn t = say("температура сорок");
            assertEquals(Turn.Action.TRANSFER, t.action());
            assertEquals("R-11", t.rule());
        }

        @Test
        @DisplayName("просьба человека — немедленный перевод, без уговоров (R-02)")
        void operatorRequest() {
            Turn t = say("соедините с человеком");
            assertEquals(Turn.Action.TRANSFER, t.action());
            assertEquals("R-02", t.rule());
        }

        @Test
        @DisplayName("медицинский вопрос не получает совета (R-04)")
        void medicalQuestion() {
            Turn t = say("что выпить от давления");
            assertEquals(Turn.Action.TRANSFER, t.action());
            assertEquals("R-04", t.rule());
            assertFalse(t.reply().toLowerCase().contains("выпейте"));
        }

        @Test
        @DisplayName("«вы робот?» — честный ответ (3.2)")
        void robotQuestion() {
            Turn t = say("вы робот");
            assertEquals(Turn.Action.HANGUP, t.action());
            assertTrue(t.reply().contains("виртуальный помощник"), t.reply());
        }

        @Test
        @DisplayName("три неудачных ответа подряд — автоматический перевод (R-05)")
        void misunderstandingLadder() {
            Turn a = say("ффф");
            Turn b = say("щщщ");
            Turn c = say("ъъъ");
            assertEquals(Turn.Action.CONTINUE, a.action());
            assertEquals(Turn.Action.CONTINUE, b.action());
            assertEquals(Turn.Action.TRANSFER, c.action());
            assertEquals("R-05", c.rule());
        }

        @Test
        @DisplayName("отказ от записи разговора помечается в сессии (3.4)")
        void recordingOptOut() {
            say("я против записи разговора");
            assertTrue(session.isRecordingOptOut());
        }
    }

    @Nested
    @DisplayName("Формат ответа пациенту (регламент 3.2)")
    class ReplyFormat {

        @Test
        @DisplayName("дата и время озвучиваются словами, а не цифрами")
        void spokenDates() {
            say("хочу записаться к стоматологу");
            Turn t = say("Смирнова Ольга Викторовна, двенадцатое марта семьдесят восьмого");
            String reply = t.reply();
            assertFalse(reply.matches(".*\\d{4}-\\d{2}-\\d{2}.*"),
                    "ISO-дата в трубку попадать не должна: " + reply);
            assertFalse(reply.matches(".*\\d{1,2}:\\d{2}.*"),
                    "цифровое время в трубку попадать не должно: " + reply);
        }

        @Test
        @DisplayName("реплика не длиннее 20 слов (регламент 3.2)")
        void atMostTwentyWords() {
            say("хочу записаться к стоматологу");
            Turn t = say("Смирнова Ольга Викторовна, двенадцатое марта семьдесят восьмого");
            int words = t.reply().split("[\\s,.;:!?—-]+").length;
            assertTrue(words <= 20,
                    "в реплике " + words + " слов (норма 20): " + t.reply());

            Turn confirm = say("второй");
            int cw = confirm.reply().split("[\\s,.;:!?—-]+").length;
            assertTrue(cw <= 20, "в подтверждении " + cw + " слов: " + confirm.reply());
        }

        @Test
        @DisplayName("предлагается не более трёх вариантов")
        void atMostThreeOptions() {
            say("хочу записаться к стоматологу");
            Turn t = say("Смирнова Ольга Викторовна, двенадцатое марта семьдесят восьмого");
            assertTrue(t.options().size() <= 3, "вариантов: " + t.options().size());
        }

        @Test
        @DisplayName("выбор варианта номером и по смыслу")
        void pickByNumberAndByMeaning() {
            say("хочу записаться к стоматологу");
            Turn t = say("Смирнова Ольга Викторовна, двенадцатое марта семьдесят восьмого");
            List<Turn.Slot> offered = t.options();
            assertEquals(3, offered.size());

            // «второй» -> второй вариант
            assertEquals(offered.get(1).date(), flow.pickSlot(offered, "второй").date());
            // названная дата выбирает нужный слот
            String target = offered.get(2).date().toString();
            assertEquals(offered.get(2).date(), flow.pickSlot(offered, "на " + target).date());
            // «вторник» — это ДЕНЬ НЕДЕЛИ, а не «второй вариант»
            var byWeekday = flow.pickSlot(offered, "вторник");
            if (byWeekday != null) {
                assertEquals(byWeekday.date().getDayOfWeek(), java.time.DayOfWeek.TUESDAY,
                        "«вторник» должен пониматься как день недели");
            }
        }
    }
}