package com.clinic.backend.specialty;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Специальность врача и список лечимых заболеваний (контракт фронтенда:
 * GET /api/specialties -> [{name, diseases: [...]}]).
 * Используется ещё и как часть базы знаний RAG (symptom -> specialty).
 */
@Entity
@Table(name = "specialties")
public class Specialty {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String name;

    // EAGER: open-in-view=false, иначе JSON-сериализация падает вне сессии
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "specialty_diseases", joinColumns = @JoinColumn(name = "specialty_id"))
    @OrderColumn(name = "idx")
    @Column(name = "disease", length = 120)
    private List<String> diseases = new ArrayList<>();

    protected Specialty() {
        // for JPA
    }

    public Specialty(String name, List<String> diseases) {
        this.name = name;
        this.diseases = new ArrayList<>(diseases);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<String> getDiseases() {
        return diseases;
    }
}
