package com.clinic.backend.voice;

import com.clinic.backend.appointment.AppointmentRepository;
import com.clinic.backend.doctor.Doctor;
import com.clinic.backend.doctor.DoctorRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Подбор свободного слота: специальность + временное окно -> конкретный
 * врач/дата/время. Учитывает расписание врача (дни недели, фиксированное время)
 * и занятость (CONFIRMED-записи).
 */
@Component
public class SlotResolver {

    private final DoctorRepository doctors;
    private final AppointmentRepository appointments;

    public SlotResolver(DoctorRepository doctors, AppointmentRepository appointments) {
        this.doctors = doctors;
        this.appointments = appointments;
    }

    public record Slot(Doctor doctor, LocalDate date, String time) {
    }

    /** Специалист ли вообще есть по этой специальности. */
    public boolean specialtyStaffed(String specialty) {
        return specialty != null && !doctors.findBySpecialty(specialty).isEmpty();
    }

    public Optional<Slot> findSlot(String specialty, TimeResolver.TimePreference pref, LocalDate today) {
        List<Doctor> candidates = doctors.findBySpecialty(specialty);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        candidates.sort(Comparator.comparing(Doctor::getTime));
        LocalDate start = pref.date() != null ? pref.date() : today;
        for (int i = 0; i <= 30; i++) {
            LocalDate date = start.plusDays(i);
            for (Doctor doctor : candidates) {
                if (!doctor.worksOn(date)) {
                    continue;
                }
                if (!pref.matches(doctor.getTime())) {
                    continue;
                }
                boolean taken = appointments
                        .findByDoctorIdAndDateAndTimeAndStatus(doctor.getId(), date, doctor.getTime(), "CONFIRMED")
                        .isPresent();
                if (!taken) {
                    return Optional.of(new Slot(doctor, date, doctor.getTime()));
                }
            }
        }
        return Optional.empty();
    }

    /** Свободные слоты врача на ближайшие N дней (для подсказок в диалоге). */
    public List<Slot> upcomingSlots(Doctor doctor, LocalDate from, int days) {
        List<Slot> result = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate date = from.plusDays(i);
            if (!doctor.worksOn(date)) {
                continue;
            }
            boolean taken = appointments
                    .findByDoctorIdAndDateAndTimeAndStatus(doctor.getId(), date, doctor.getTime(), "CONFIRMED")
                    .isPresent();
            if (!taken) {
                result.add(new Slot(doctor, date, doctor.getTime()));
            }
        }
        return result;
    }
}
