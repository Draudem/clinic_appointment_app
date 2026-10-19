package com.clinic.backend.voice;

import com.clinic.backend.appointment.Appointment;
import com.clinic.backend.appointment.AppointmentRepository;
import com.clinic.backend.mis.MisClock;
import com.clinic.backend.user.User;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * С-10: Исходящий звонок-напоминание.
 *
 * <p>Выбирает записи на завтра (CONFIRMED) и формирует план обзвона.
 * Фиксация результатов обзвона позволяет вести статистику неявок.
 *
 * <p>Реализован каркас: планирование и запись результатов.
 * Интеграция с Asterisk AMI для реального Originate — этап 2 пилота.
 */
@Service
public class ReminderService {

    private final AppointmentRepository appointments;
    private final MisClock clock;

    public ReminderService(AppointmentRepository appointments, MisClock clock) {
        this.appointments = appointments;
        this.clock = clock;
    }

    /**
     * План обзвона на завтра.
     * Возвращает список задач: кому звонить и по какому поводу.
     */
    public List<ReminderTask> planForTomorrow() {
        LocalDate tomorrow = clock.today().plusDays(1);

        // В реальной системе здесь был бы findByDateAndStatus(tomorrow, "CONFIRMED").
        // Но в AppointmentRepository пока нет такого метода (только для конкретного врача),
        // поэтому используем findAll и фильтруем в памяти (записей в БД немного).
        List<ReminderTask> tasks = new ArrayList<>();

        for (Appointment a : appointments.findAll()) {
            if (a.getDate().equals(tomorrow) && "CONFIRMED".equals(a.getStatus())) {
                User p = a.getPatient();
                String patientName = p != null ? p.getAddressName() : "Пациент";
                String slot = a.getDate() + " " + a.getTime();
                String doctor = a.getDoctor().getFio();

                tasks.add(new ReminderTask(a.getId(), a.getDoctor().getId(), doctor, slot, patientName, 0));
            }
        }
        return tasks;
    }

    /**
     * Зафиксировать результат обзвона.
     * В полноценной системе это обновляло бы статус в МИС (PATCH /visits/{id})
     * или создавало бы запись об отказе.
     */
    public void markResult(Long appointmentId, String resultCode) {
        // Заглушка для фиксации: логируем факт.
        // В следующей итерации здесь будет вызов RestMisClient.
        org.slf4j.LoggerFactory.getLogger(ReminderService.class)
                .info("С-10: запись #{} обзвонена, результат: {}", appointmentId, resultCode);
    }

    /**
     * Задача обзвона.
     *
     * @param appointmentId ID записи в БД
     * @param doctorId ID врача
     * @param doctorName ФИО врача
     * @param slot когда приём ("2026-10-20 09:30")
     * @param patientName имя пациента для обращения
     * @param attempts сколько раз пытались дозвониться
     */
    public record ReminderTask(
            Long appointmentId,
            Long doctorId,
            String doctorName,
            String slot,
            String patientName,
            int attempts) {
    }
}