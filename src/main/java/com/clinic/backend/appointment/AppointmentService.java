package com.clinic.backend.appointment;

import com.clinic.backend.auth.BadCredentialsException;
import com.clinic.backend.common.ConflictException;
import com.clinic.backend.common.ForbiddenException;
import com.clinic.backend.common.NotFoundException;
import com.clinic.backend.doctor.Doctor;
import com.clinic.backend.doctor.DoctorRepository;
import com.clinic.backend.user.User;
import com.clinic.backend.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Записи пациентов. Правила:
 *  - слот = врач + дата + время приёма врача;
 *  - врач должен работать в выбранную дату;
 *  - занятый слот -> ConflictException.
 */
@Service
public class AppointmentService {

    private final AppointmentRepository appointments;
    private final DoctorRepository doctors;
    private final UserRepository users;

    public AppointmentService(AppointmentRepository appointments,
                              DoctorRepository doctors,
                              UserRepository users) {
        this.appointments = appointments;
        this.doctors = doctors;
        this.users = users;
    }

    @Transactional
    public Map<String, Object> book(String patientLogin, Long doctorId, LocalDate date, String time) {
        User patient = users.findByLogin(patientLogin)
                .orElseThrow(BadCredentialsException::new);
        Doctor doctor = doctors.findById(doctorId)
                .orElseThrow(() -> new NotFoundException("Врач не найден: " + doctorId));
        if (!doctor.worksOn(date)) {
            throw new ConflictException("Врач " + doctor.getFio() + " не работает " + date);
        }
        if (time == null || time.isBlank()) {
            time = doctor.getTime();
        }
        if (!doctor.getTime().equals(time)) {
            throw new ConflictException("Время приёма врача: " + doctor.getTime());
        }
        boolean taken = appointments
                .findByDoctorIdAndDateAndTimeAndStatus(doctor.getId(), date, time, "CONFIRMED")
                .isPresent();
        if (taken) {
            throw new ConflictException("Слот " + date + " " + time + " уже занят");
        }
        Appointment appointment = appointments.save(new Appointment(patient, doctor, date, time));
        return toDto(appointment);
    }

    @Transactional
    public void cancel(String userLogin, Long appointmentId, boolean isAdmin) {
        Appointment appointment = appointments.findById(appointmentId)
                .orElseThrow(() -> new NotFoundException("Запись не найдена: " + appointmentId));
        if (!isAdmin && !appointment.getPatient().getLogin().equals(userLogin)) {
            throw new ForbiddenException("Можно отменять только свои записи");
        }
        appointment.cancel();
        appointments.save(appointment);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listFor(String userLogin, boolean isAdmin) {
        User user = users.findByLogin(userLogin)
                .orElseThrow(BadCredentialsException::new);
        List<Appointment> list = isAdmin
                ? appointments.findAll()
                : appointments.findByPatientIdOrderByDateAscTimeAsc(user.getId());
        return list.stream().map(AppointmentService::toDto).toList();
    }

    public static Map<String, Object> toDto(Appointment a) {
        String patientName = null;
        if (a.getPatient() != null) {
            User p = a.getPatient();
            String first = p.getFirstName() == null ? "" : p.getFirstName();
            String last = p.getLastName() == null ? "" : p.getLastName();
            String joined = (last + " " + first).trim();
            patientName = joined.isEmpty() ? p.getLogin() : joined;
        }
        return Map.of(
                "id", a.getId(),
                "doctorId", a.getDoctor().getId(),
                "doctorName", a.getDoctor().getFio(),
                "specialty", a.getDoctor().getSpecialty(),
                "date", a.getDate().toString(),
                "time", a.getTime(),
                "status", a.getStatus(),
                "patientName", patientName == null ? "" : patientName);
    }
}
