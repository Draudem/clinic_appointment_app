package com.clinic.assistant.clinic;

import com.clinic.assistant.common.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Специальности и заболевания (вкладка «Специальности»). */
@RestController
@RequestMapping("/api/specialties")
@Tag(name = "Специальности")
public class SpecialtyController {

    private final SpecialtyRepository specialtyRepository;

    public SpecialtyController(SpecialtyRepository specialtyRepository) {
        this.specialtyRepository = specialtyRepository;
    }

    public record SpecialtyDto(String name, List<String> diseases) {
    }

    @GetMapping
    @Operation(summary = "Все специальности с заболеваниями")
    public List<SpecialtyDto> all() {
        return specialtyRepository.findAll().stream()
                .map(s -> new SpecialtyDto(s.getName(), s.getDiseases()))
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Специальность по id")
    public SpecialtyDto one(@PathVariable Long id) {
        Specialty specialty = specialtyRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Специальность не найдена: " + id));
        return new SpecialtyDto(specialty.getName(), specialty.getDiseases());
    }
}
