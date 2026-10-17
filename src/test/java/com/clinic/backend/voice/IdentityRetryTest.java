package com.clinic.backend.voice;

import com.clinic.backend.appointment.AppointmentService;
import com.clinic.backend.doctor.DoctorRepository;
import com.clinic.backend.identity.PatientIdentity;
import com.clinic.backend.identity.PatientIdentityResolver;
import com.clinic.backend.mis.MisClient;
import com.clinic.backend.mis.dto.MisClientDto;
import com.clinic.backend.mis.dto.MisVisit;
import com.clinic.backend.schedule.ScheduleEntry;
import com.clinic.backend.schedule.ScheduleProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdentityRetryTest {

    private BookingFlow flow;
    private PatientIdentityResolver identity;
    private SlotResolver slots;
    private AppointmentService appointments;
    private DialogueSession session;
    private MisClient misClient; // <--- ДОБАВЛЕНО

    private static final PatientIdentity ANY =
            new PatientIdentity(1L, "smirnova", "Смирнова", "Ольга", "Викторовна",
                    LocalDate.of(1978, 3, 12), "+79001234567", "test");

    @BeforeEach
    void setUp() {
        appointments = mock(AppointmentService.class);
        identity = mock(PatientIdentityResolver.class);
        slots = mock(SlotResolver.class);
        misClient = mock(MisClient.class); // <--- ДОБАВЛЕНО
        CommandParser parser = mock(CommandParser.class);

        when(parser.parse(anyString())).thenReturn(new ParsedCommand("BOOK", "Стоматолог",
                List.of(), List.of(), false, false, false, "", "test"));
        when(slots.specialtyStaffed(anyString())).thenReturn(true);
        when(slots.offerSlots(anyString(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(testSlots());
        when(appointments.listFor(anyString(), any(Boolean.class))).thenReturn(List.of());
        when(appointments.book(anyString(), any(), any(), anyString()))
                .thenReturn(java.util.Map.of("id", 1L, "doctorName", "Смирнов Алексей Викторович",
                        "specialty", "Стоматолог", "date", LocalDate.now().plusDays(1).toString(),
                        "time", "11:00", "status", "CONFIRMED"));

        when(identity.findByLogin(anyString())).thenReturn(Optional.of(ANY)); // <--- ДОБАВЛЕНО

        flow = new BookingFlow(parser, slots, appointments, identity,
                mock(DialogueSessionRepository.class), new ScheduleQueryService(
                mock(DoctorRepository.class), mock(ScheduleProvider.class)),
                misClient); // <--- ДОБАВЛЕНО
        session = new DialogueSession("");
    }

    private Turn say(String text) {
        return flow.handle(session, PatientIdentity.unknown(), text);
    }

    private List<SlotResolver.Slot> testSlots() {
        List<SlotResolver.Slot> list = new ArrayList<>();
        list.add(new SlotResolver.Slot(new ScheduleEntry(3L, "Смирнов Алексей Викторович",
                "Стоматолог", LocalDate.now().plusDays(1), "09:00", 201)));
        return list;
    }

    @Test
    @DisplayName("несуществующий пациент: переспрос один раз, потом перевод оператору")
    void transferAfterSecondFailure() {
        when(identity.findByFioAndBirthDate(any(), any())).thenReturn(Optional.empty());

        Turn t1 = say("хочу записаться к стоматологу");
        assertEquals("IDENTITY", t1.stage(), "сначала просим назвать себя");

        Turn t2 = say("Ганеев Тимур Айратович, шестое января тысяча девятьсот девяностого");
        assertEquals("IDENTITY", t2.stage(), "первая неудача — ещё один переспрос");
        assertTrue(t2.reply().contains("Не получается найти"), t2.reply());

        Turn t3 = say("Ганеев Тимур Айратович, шестое января тысяча девятьсот девяностого");
        assertEquals("OPERATOR", t3.stage(), "вторая неудача — перевод");
        assertEquals(Turn.Action.TRANSFER, t3.action());
    }

    @Test
    @DisplayName("названное не теряется между ходами: ФИО, потом дата")
    void remembersWhatWasSaid() {
        when(identity.findByFioAndBirthDate(any(), any())).thenReturn(Optional.of(ANY));

        say("хочу записаться к стоматологу");
        Turn fio = say("Петров Игорь Николаевич");
        assertEquals("IDENTITY", fio.stage());
        assertTrue(fio.reply().contains("дату рождения"), fio.reply());

        Turn dob = say("двенадцатое марта тысяча девятьсот семьдесят восьмого");
        assertFalse(dob.reply().contains("фамилию"), "фамилию уже называли");
        assertEquals("PENDING_CONFIRMATION", dob.stage());
    }
}