package com.clinic.assistant.clinic;

import com.clinic.assistant.common.BadRequestException;
import com.clinic.assistant.common.ConflictException;
import com.clinic.assistant.common.NotFoundException;
import com.clinic.assistant.security.AuthPrincipal;
import com.clinic.assistant.user.User;
import com.clinic.assistant.user.UserRepository;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Создание, просмотр и отмена записей на приём.
 *
 * <p>Правила: слот должен существовать в еженедельном расписании врача,
 * быть в будущем и быть свободным (на слот — одна активная запись).</p>
 */
@Service
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final DoctorRepository doctorRepository;
    private final ScheduleService scheduleService;
    private final UserRepository userRepository;

    public AppointmentService(AppointmentRepository appointmentRepository,
                              DoctorRepository doctorRepository,
                              ScheduleService scheduleService,
                              UserRepository userRepository) {
        this.appointmentRepository = appointmentRepository;
        this.doctorRepository = doctorRepository;
        this.scheduleService = scheduleService;
        this.userRepository = userRepository;
    }

    public record CreateRequest(
            @NotNull(message = "Укажите врача") Long doctorId,
            @NotNull(message = "Укажите дату") LocalDate date,
            @NotNull(message = "Укажите время") LocalTime time) {
    }

    public record AppointmentDto(Long id,
                                 Long doctorId,
                                 String fio,
                                 String specialty,
                                 String patient,
                                 String date,
                                 String time,
                                 String status,
                                 String createdAt) {
    }

    @Transactional
    public AppointmentDto create(AuthPrincipal principal, CreateRequest request) {
        Doctor doctor = doctorRepository.findById(request.doctorId())
                .filter(Doctor::isActive)
                .orElseThrow(() -> new NotFoundException("Врач не найден: " + request.doctorId()));

        if (request.date().isBefore(LocalDate.now())) {
            throw new BadRequestException("Нельзя записаться в прошедшую дату");
        }
        if (!scheduleService.hasWeeklySlot(doctor.getId(), request.date(), request.time())) {
            throw new BadRequestException("У врача «" + doctor.getFio()
                    + "» нет приёма в это время. Посмотрите расписание.");
        }
        if (appointmentRepository.existsByDoctorIdAndDateAndStartTimeAndStatus(
                doctor.getId(), request.date(), request.time(), AppointmentStatus.BOOKED)) {
            throw new ConflictException("Это время уже занято — выберите другое");
        }

        User patient = userRepository.findById(principal.id())
                .orElseThrow(() -> new NotFoundException("Пользователь не найден"));

        Appointment appointment = new Appointment(doctor, patient, request.date(), request.time());
        appointmentRepository.save(appointment);
        return toDto(appointment);
    }

    /** Свои записи (пациент) или все записи (администратор, с фильтрами). */
    @Transactional(readOnly = true)
    public List<AppointmentDto> list(AuthPrincipal principal, LocalDate date, Long doctorId) {
        List<Appointment> result;
        if (principal.isAdmin()) {
            result = date != null
                    ? appointmentRepository.findForDate(AppointmentStatus.BOOKED, date, doctorId)
                    : appointmentRepository.findByStatusOrderByDateAscStartTimeAsc(AppointmentStatus.BOOKED);
            if (doctorId != null && date == null) {
                final Long filter = doctorId;
                result = result.stream().filter(a -> a.getDoctor().getId().equals(filter)).toList();
            }
        } else {
            result = appointmentRepository
                    .findByPatientIdAndStatusOrderByDateAscStartTimeAsc(principal.id(), AppointmentStatus.BOOKED);
        }
        return result.stream().map(this::toDto).toList();
    }

    /** Отмена записи: своей (пациент) или любой (администратор). */
    @Transactional
    public void cancel(AuthPrincipal principal, Long appointmentId) {
        Appointment appointment = principal.isAdmin()
                ? appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new NotFoundException("Запись не найдена: " + appointmentId))
                : appointmentRepository.findByIdAndPatientId(appointmentId, principal.id())
                .orElseThrow(() -> new NotFoundException("Запись не найдена: " + appointmentId));

        if (appointment.getStatus() != AppointmentStatus.BOOKED) {
            throw new ConflictException("Запись уже неактивна (статус: " + appointment.getStatus() + ")");
        }
        appointment.setStatus(AppointmentStatus.CANCELLED);
        appointmentRepository.save(appointment);
    }

    private AppointmentDto toDto(Appointment a) {
        return new AppointmentDto(
                a.getId(),
                a.getDoctor().getId(),
                a.getDoctor().getFio(),
                a.getDoctor().getSpecialty().getName(),
                a.getPatient() != null ? a.getPatient().getLogin() : null,
                a.getDate().toString(),
                a.getStartTime().toString(),
                a.getStatus().name(),
                a.getCreatedAt().toString());
    }
}
