package com.clinic.backend.doctor;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Врач. Поля совпадают с контрактом фронтенда (js/data.js):
 * fio, specialty, time ("09:00"), days — дни недели в нумерации JS Date.getDay()
 * (0=Вс..6=Сб), хранятся строкой "1,3,5".
 */
@Entity
@Table(name = "doctors")
public class Doctor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String fio;

    @Column(nullable = false, length = 80)
    private String specialty;

    /** Время приёма в формате HH:mm. */
    @Column(nullable = false, length = 5)
    private String time;

    /** Дни недели JS (0-6) через запятую, например "1,3,5". */
    @Column(nullable = false, length = 30)
    private String days;

    protected Doctor() {
        // for JPA
    }

    public Doctor(String fio, String specialty, String time, List<Integer> days) {
        this.fio = fio;
        this.specialty = specialty;
        this.time = time;
        this.days = join(days);
    }

    public static String join(List<Integer> days) {
        return days.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
    }

    public List<Integer> getDaysList() {
        List<Integer> result = new ArrayList<>();
        for (String part : days.split(",")) {
            if (!part.isBlank()) {
                result.add(Integer.parseInt(part.trim()));
            }
        }
        return result;
    }

    /** Работает ли врач в указанную дату (дни в нумерации JS getDay). */
    public boolean worksOn(LocalDate date) {
        int jsDay = date.getDayOfWeek().getValue() % 7; // Пн=1..Сб=6, Вс=0
        return getDaysList().contains(jsDay);
    }

    /** Ближайшая дата приёма начиная с from (включительно). */
    public LocalDate nextWorkDate(LocalDate from) {
        LocalDate candidate = from;
        for (int i = 0; i < 14; i++) {
            if (worksOn(candidate)) {
                return candidate;
            }
            candidate = candidate.plusDays(1);
        }
        return null;
    }

    public Long getId() {
        return id;
    }

    public String getFio() {
        return fio;
    }

    public String getSpecialty() {
        return specialty;
    }

    public String getTime() {
        return time;
    }

    public String getDays() {
        return days;
    }
}
