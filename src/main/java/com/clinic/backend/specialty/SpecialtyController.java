package com.clinic.backend.specialty;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Контракт фронтенда (js/api.js -> getSpecialties):
 *   GET /api/specialties -> [ {name, diseases: [...]} ]
 */
@RestController
@RequestMapping("/api/specialties")
@Tag(name = "Specialties", description = "Специальности и лечимые заболевания")
public class SpecialtyController {

    private final SpecialtyRepository specialties;

    public SpecialtyController(SpecialtyRepository specialties) {
        this.specialties = specialties;
    }

    @GetMapping
    public List<Specialty> all() {
        return specialties.findAll();
    }
}
