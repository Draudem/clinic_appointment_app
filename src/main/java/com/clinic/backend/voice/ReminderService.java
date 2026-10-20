package com.clinic.backend.voice;

import com.clinic.backend.appointment.Appointment;
import com.clinic.backend.appointment.AppointmentRepository;
import com.clinic.backend.mis.MisClock;
import com.clinic.backend.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class ReminderService {

    private final AppointmentRepository appointments;
    private final MisClock clock;

    public ReminderService(AppointmentRepository appointments, MisClock clock) {
        this.appointments = appointments;
        this.clock = clock;
    }

    @Transactional(readOnly = true) // <--- ФИКС LAZY INITIALIZATION
    public List<ReminderTask> planForTomorrow() {
        LocalDate tomorrow = clock.today().plusDays(1);
        List<ReminderTask> tasks = new ArrayList<>();

        for (Appointment a : appointments.findAll()) {
            if (a.getDate().equals(tomorrow) && "CONFIRMED".equals(a.getStatus())) {
                User p = a.getPatient();
                String patientName = p != null ? p.getAddressName() : "Пациент";
                String phone = (p != null) ? p.getPhone() : "+70000000000"; // Заглушка, если нет телефона
                String slot = a.getDate() + " " + a.getTime();
                String doctor = a.getDoctor().getFio();

                tasks.add(new ReminderTask(a.getId(), a.getDoctor().getId(), doctor, slot, patientName, phone, 0));
            }
        }
        return tasks;
    }

    public void markResult(Long appointmentId, String resultCode) {
        org.slf4j.LoggerFactory.getLogger(ReminderService.class)
                .info("С-10: запись #{} обзвонена, результат: {}", appointmentId, resultCode);
    }

    public record ReminderTask(
            Long appointmentId,
            Long doctorId,
            String doctorName,
            String slot,
            String patientName,
            String phone,
            int attempts) {
    }
}