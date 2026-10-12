package com.clinic.backend.user;

import com.clinic.backend.appointment.AppointmentRepository;
import com.clinic.backend.common.ConflictException;
import com.clinic.backend.common.NotFoundException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Операции над пользователями со стороны администратора.
 * Пароль здесь не используется: администратору он не показывается.
 */
@Service
public class UserService {

    private final UserRepository users;
    private final AppointmentRepository appointments;

    public UserService(UserRepository users, AppointmentRepository appointments) {
        this.users = users;
        this.appointments = appointments;
    }

    @Transactional(readOnly = true)
    public List<UserDto> listAll() {
        return users.findAll(Sort.by(Sort.Direction.ASC, "id"))
                .stream()
                .map(UserDto::from)
                .toList();
    }

    /**
     * Удаление пользователя. Правила:
     *  - нельзя удалить самого себя (иначе можно потерять доступ к системе);
     *  - нельзя удалить единственного администратора;
     *  - нельзя удалить пациента с активными (CONFIRMED) записями —
     *    сначала их нужно отменить, иначе слоты останутся занятыми
     *    несуществующим пациентом.
     * Уже отменённые (CANCELLED) записи удаляются вместе с пользователем.
     *
     * @param currentLogin логин администратора, выполняющего удаление
     */
    @Transactional
    public void delete(Long id, String currentLogin) {
        User target = users.findById(id)
                .orElseThrow(() -> new NotFoundException("Пользователь не найден: " + id));

        if (target.getLogin().equals(currentLogin)) {
            throw new ConflictException("Нельзя удалить собственную учётную запись");
        }
        if (target.getRole() == Role.ADMIN && users.countByRole(Role.ADMIN) <= 1) {
            throw new ConflictException("Нельзя удалить единственного администратора");
        }
        long active = appointments.countByPatientIdAndStatus(id, "CONFIRMED");
        if (active > 0) {
            throw new ConflictException("У пациента есть активные записи (" + active
                    + ") — сначала отмените их, затем удалите пользователя");
        }

        appointments.deleteByPatientIdAndStatus(id, "CANCELLED");
        users.delete(target);
    }
}