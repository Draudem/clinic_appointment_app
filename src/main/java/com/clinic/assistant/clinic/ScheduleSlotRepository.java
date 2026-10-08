package com.clinic.assistant.clinic;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ScheduleSlotRepository extends JpaRepository<ScheduleSlot, Long> {

    /** Все недельные окна приёма вместе с врачом и специальностью (без N+1). */
    @EntityGraph(attributePaths = {"doctor", "doctor.specialty"})
    List<ScheduleSlot> findAll();

    @EntityGraph(attributePaths = {"doctor", "doctor.specialty"})
    List<ScheduleSlot> findByDoctorIdIn(Collection<Long> doctorIds);

    @EntityGraph(attributePaths = {"doctor", "doctor.specialty"})
    List<ScheduleSlot> findByDoctor_Id(Long doctorId);
}
