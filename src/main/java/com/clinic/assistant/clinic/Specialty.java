package com.clinic.assistant.clinic;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

/** Специальность врача и список характерных заболеваний (для вкладки «Специальности»). */
@Entity
@Table(name = "specialty")
public class Specialty {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "specialty_disease", joinColumns = @JoinColumn(name = "specialty_id"))
    @Column(name = "disease", nullable = false, length = 128)
    private List<String> diseases = new ArrayList<>();

    protected Specialty() {
    }

    public Specialty(String name) {
        this.name = name;
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

    public void setDiseases(List<String> diseases) {
        this.diseases = diseases;
    }
}
