package com.clinic.assistant.clinic;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Таблица сотрудников: ФИО, специальность, ближайшее время записи.
 *
 * <pre>GET /api/employees → [{doctorId, fio, specialty, nextSlot, time, days}]</pre>
 */
@RestController
@RequestMapping("/api/employees")
@Tag(name = "Сотрудники")
public class EmployeeController {

    private final DoctorViewService views;

    public EmployeeController(DoctorViewService views) {
        this.views = views;
    }

    @GetMapping
    @Operation(summary = "Сотрудники клиники с ближайшим свободным временем записи")
    public List<DoctorViewService.EmployeeDto> all() {
        return views.employees();
    }
}
