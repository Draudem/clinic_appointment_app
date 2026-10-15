package com.clinic.backend.schedule;

import com.clinic.backend.doctor.Doctor;
import com.clinic.backend.doctor.DoctorRepository;
import com.clinic.backend.support.ScheduleExcelGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Расписание из Excel — вторая реализация порта ScheduleProvider.
 *
 * Проверяем главное: файл читается, строки сопоставляются с врачами из БД,
 * неизвестные врачи пропускаются, а файл перечитывается при изменении.
 */
class ExcelScheduleProviderTest {

    private static final LocalDate FROM = LocalDate.of(2026, 11, 2);   // понедельник

    @TempDir
    Path dir;

    private DoctorRepository doctors;

    @BeforeEach
    void setUp() {
        doctors = mock(DoctorRepository.class);
        // список собираем заранее: создавать моки внутри thenReturn нельзя,
        // Mockito ещё не закончил настройку предыдущего вызова
        List<Doctor> catalog = List.of(
                doctor(1L, "Смирнов Алексей Викторович", "Стоматолог"),
                doctor(2L, "Иванов Пётр Сергеевич", "Терапевт"),
                doctor(3L, "Морозова Ольга Ивановна", "ЛОР"));
        when(doctors.findAll()).thenReturn(catalog);
    }

    /**
     * Врач с настоящим id: без него строка Excel не сопоставится со справочником
     * и будет пропущена — так и должно быть в бою, запись без врача невозможна.
     */
    private static Doctor doctor(Long id, String fio, String specialty) {
        Doctor d = mock(Doctor.class);
        when(d.getId()).thenReturn(id);
        when(d.getFio()).thenReturn(fio);
        when(d.getSpecialty()).thenReturn(specialty);
        return d;
    }

    private ExcelScheduleProvider provider(String fileName) {
        ScheduleProperties props = new ScheduleProperties();
        props.getExcel().setPath(dir.resolve(fileName).toString());
        return new ExcelScheduleProvider(doctors, props);
    }

    @Test
    @DisplayName("файл читается и даёт слоты по специальности")
    void readsFile() {
        Path file = dir.resolve("schedule.xlsx");
        ScheduleExcelGenerator.write(file, FROM);

        ExcelScheduleProvider p = provider("schedule.xlsx");
        assertTrue(p.health().readable(), () -> "ожидался читаемый файл, ошибка: " + p.health().error());

        List<ScheduleEntry> dentist = p.bySpecialty("Стоматолог", FROM, FROM.plusDays(13));
        assertFalse(dentist.isEmpty(), "стоматолог должен иметь приёмы");
        assertEquals("Смирнов Алексей Викторович", dentist.get(0).fio());
        assertEquals(201, dentist.get(0).cabinet());
        assertNotNull(dentist.get(0).doctorId());
    }

    @Test
    @DisplayName("кабинет и несколько времён в день читаются как есть")
    void readsMultipleTimesPerDay() {
        ScheduleExcelGenerator.write(dir.resolve("s.xlsx"), FROM);
        ExcelScheduleProvider p = provider("s.xlsx");

        List<ScheduleEntry> firstDay = p.byDoctorFio("Смирнов Алексей Викторович", FROM, FROM);
        assertEquals(3, firstDay.size(), "в понедельник должно быть три приёма");
        assertTrue(firstDay.stream().anyMatch(e -> e.time().equals("09:00")));
        assertTrue(firstDay.stream().anyMatch(e -> e.time().equals("11:00")));
        assertTrue(firstDay.stream().anyMatch(e -> e.time().equals("15:00")));
    }

    @Test
    @DisplayName("поиск конкретного слота врача в конкретное время")
    void exactLookup() {
        ScheduleExcelGenerator.write(dir.resolve("s.xlsx"), FROM);
        ExcelScheduleProvider p = provider("s.xlsx");

        assertTrue(p.exact("Смирнов Алексей Викторович", FROM, "11:00").isPresent());
        assertTrue(p.exact("Смирнов Алексей Викторович", FROM, "23:00").isEmpty());
        assertTrue(p.exact("Неизвестный Врач", FROM, "11:00").isEmpty());
    }

    @Test
    @DisplayName("отсутствующий файл не роняет бэкенд, а помечается в диагностике")
    void missingFileIsReportedNotFatal() {
        ExcelScheduleProvider p = provider("нет-такого-файла.xlsx");
        ScheduleProvider.Health h = p.health();
        assertFalse(h.readable());
        assertNotNull(h.error());
        assertTrue(p.bySpecialty("Стоматолог", FROM, FROM.plusDays(13)).isEmpty());
    }

    @Test
    @DisplayName("файл перечитывается, если он изменился")
    void reloadsOnChange() throws Exception {
        Path file = dir.resolve("s.xlsx");
        ScheduleExcelGenerator.write(file, FROM);
        ExcelScheduleProvider p = provider("s.xlsx");
        int before = p.bySpecialty("Терапевт", FROM, FROM.plusDays(13)).size();
        assertTrue(before > 0);

        // перезаписываем файл другим расписанием и ждём смены размера/времени
        Thread.sleep(1100);
        ScheduleExcelGenerator.writeNumericTime(file, FROM, LocalTime.of(8, 45));
        List<ScheduleEntry> after = p.bySpecialty("Стоматолог", FROM, FROM.plusDays(13));
        assertEquals(1, after.size(), "после перезаписи должен быть один приём");
        assertEquals("08:45", after.get(0).time(), "время, записанное как время Excel, должно читаться");
    }
}