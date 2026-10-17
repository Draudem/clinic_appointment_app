package com.clinic.backend.voice;

import com.clinic.backend.appointment.AppointmentService;
import com.clinic.backend.doctor.DoctorRepository;
import com.clinic.backend.identity.PatientIdentity;
import com.clinic.backend.identity.PatientIdentityResolver;
import com.clinic.backend.mis.EscalationService;
import com.clinic.backend.mis.MisClient;
import com.clinic.backend.mis.dto.MisClientDto;
import com.clinic.backend.mis.dto.MisVisit;
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

class BookingFlowTest {

    private BookingFlow flow;
    private AppointmentService appointments;
    private PatientIdentityResolver identity;
    private SlotResolver slots;
    private DialogueSession session;
    private MisClient misClient; // <--- ДОБАВЛЕНО

    private static final LocalDate TODAY = LocalDate.now();
    private static final PatientIdentity SMITH =
            new PatientIdentity(1L, "smirnova", "Смирнова", "Ольга", "Викторовна",
                    LocalDate.of(1978, 3, 12), "+79001234567", "test");

    @BeforeEach
    void setUp() {
        appointments = mock(AppointmentService.class);
        identity = mock(PatientIdentityResolver.class);
        slots = mock(SlotResolver.class);
        misClient = mock(MisClient.class); // <--- ДОБАВЛЕНО
        CommandParser parser = mock(CommandParser.class);
        EscalationService escalationService = mock(EscalationService.class);


        // Настройка мока МИС
        when(misClient.available()).thenReturn(true);
        when(misClient.findOrCreateClient(anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(new MisClientDto(1L, "Смирнова", "Ольга", "12.03.1978"));
        when(misClient.createVisit(any(), any(), anyString()))
                .thenReturn(new MisVisit(99L, 1L, new MisVisit.MisDoctor(3L, "Смирнов Алексей Викторович", "Стоматолог", true), "16.10.2026 11:00", "Запланирована", "14.10.2026 12:00"));

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
        when(identity.findByLogin(anyString())) // <--- ДОБАВЛЕНО
                .thenReturn(Optional.of(SMITH));

        DialogueSessionRepository sessions = mock(DialogueSessionRepository.class);
        ScheduleQueryService scheduleQueries = new ScheduleQueryService(
                mock(DoctorRepository.class), mock(ScheduleProvider.class));



        // Внедряем misClient в конструктор
        flow = new BookingFlow(parser, slots, appointments, identity,
                sessions, scheduleQueries, misClient, escalationService);        session = new DialogueSession("");
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

    @Nested
    @DisplayName("С-01: полный цикл записи")
    class FullCycle {
        @Test
        @DisplayName("запись создаётся только после называния ФИО, даты рождения и подтверждения")
        void bookEndToEnd() {
            Turn t1 = say("хочу записаться к стоматологу");
            assertEquals("IDENTITY", t1.stage());

            Turn t2 = say("Смирнова Ольга Викторовна, двенадцатое марта семьдесят восьмого");
            assertEquals("PENDING_CONFIRMATION", t2.stage());
            assertEquals(1, t2.options().size());

            Turn t3 = say("да");
            assertEquals(Turn.Action.HANGUP, t3.action());
            assertEquals("BOOKED", t3.resultCode());
            assertEquals("CONFIRMED", session.getStage());
        }
    }

    @Nested
    @DisplayName("Идентификация пациента")
    class Identity {
        @Test
        void noIdentityNoBooking() {
            Turn t = say("хочу записаться к стоматологу");
            assertEquals("IDENTITY", t.stage());
        }
    }

    @Nested
    @DisplayName("Сквозные правила")
    class Policy {
        @Test
        void redFlagInterrupts() {
            say("хочу записаться к стоматологу");
            Turn t = say("ой, у меня сильно давит в груди");
            assertEquals(Turn.Action.TRANSFER, t.action());
            assertEquals("R-01", t.rule());
        }
    }
}