package com.clinic.backend.schedule;

import com.clinic.backend.doctor.Doctor;
import com.clinic.backend.doctor.DoctorRepository;
import org.apache.poi.ss.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ExcelScheduleProvider implements ScheduleProvider {

    private static final Logger log = LoggerFactory.getLogger(ExcelScheduleProvider.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final Path file;
    private volatile List<ScheduleEntry> cache = List.of();
    private volatile String cacheStamp = "";
    private volatile String lastError;

    public ExcelScheduleProvider(DoctorRepository doctors, ScheduleProperties props) {
        this.file = Path.of(props.getExcel().getPath());
    }

    @Override
    public String name() {
        return "excel:" + file;
    }

    @Override
    public List<ScheduleEntry> bySpecialty(String specialty, LocalDate from, LocalDate to) {
        List<ScheduleEntry> result = new ArrayList<>();
        for (ScheduleEntry e : entries()) {
            // Ищем не только точное совпадение, но и частичное (Кардиолог == Кардиолог)
            if (specialty != null && e.specialty().toLowerCase().contains(specialty.toLowerCase())
                    && !e.date().isBefore(from) && !e.date().isAfter(to)) {
                result.add(e);
            }
        }
        return result;
    }

    @Override
    public List<ScheduleEntry> all(LocalDate from, LocalDate to) {
        List<ScheduleEntry> result = new ArrayList<>();
        for (ScheduleEntry e : entries()) {
            if (!e.date().isBefore(from) && !e.date().isAfter(to)) {
                result.add(e);
            }
        }
        return result;
    }

    @Override
    public List<ScheduleEntry> byDoctorFio(String fio, LocalDate from, LocalDate to) {
        List<ScheduleEntry> result = new ArrayList<>();
        if (fio == null) return result;
        for (ScheduleEntry e : entries()) {
            if (e.fio().equalsIgnoreCase(fio) && !e.date().isBefore(from) && !e.date().isAfter(to)) {
                result.add(e);
            }
        }
        return result;
    }

    @Override
    public Optional<ScheduleEntry> exact(String fio, LocalDate date, String time) {
        if (fio == null || date == null || time == null) return Optional.empty();
        return byDoctorFio(fio, date, date).stream()
                .filter(e -> e.time().equals(time))
                .findFirst();
    }

    @Override
    public Health health() {
        List<ScheduleEntry> rows = entries();
        String err = lastError;
        return new Health(err == null, name(), rows.size(), err);
    }

    private List<ScheduleEntry> entries() {
        String stamp;
        try {
            if (!Files.isReadable(file)) {
                lastError = "Файл расписания недоступен: " + file.toAbsolutePath();
                cache = List.of();
                return cache;
            }
            stamp = Files.size(file) + "@" + Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            lastError = "Не удалось опросить файл: " + e.getMessage();
            cache = List.of();
            return cache;
        }

        if (stamp.equals(cacheStamp)) return cache;

        try (InputStream in = Files.newInputStream(file);
             Workbook wb = WorkbookFactory.create(in)) {

            // Ищем лист с расписанием (по умолчанию первый)
            Sheet sheet = wb.getSheetAt(0);
            List<ScheduleEntry> parsed = parseSheet(sheet);

            cacheStamp = stamp;
            cache = List.copyOf(parsed);
            lastError = null;
            log.info("Расписание из Excel прочитано: {} строк из {}", parsed.size(), file.getFileName());
        } catch (Exception e) {
            lastError = "Ошибка чтения: " + e.getMessage();
            cache = List.of();
            log.error("Ошибка чтения расписания из {}", file, e);
        }
        return cache;
    }

    private List<ScheduleEntry> parseSheet(Sheet sheet) {
        List<ScheduleEntry> result = new ArrayList<>();

        // Пропускаем заголовки (обычно 1-2 строки)
        int startRow = 1;

        for (int i = startRow; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;

            try {
                // Предполагаем стандартные колонки:
                // 0 - ID слота, 1 - Дата, 2 - День, 3 - ID врача, 4 - ФИО, 5 - Специальность, 6 - Отделение, 7 - Кабинет, 8 - Начало, 9 - Статус

                String dateStr = getStringCell(row, 1); // Дата
                String doctorIdStr = getStringCell(row, 3); // ID врача (D001)
                String fio = getStringCell(row, 4); // ФИО
                String specialty = getStringCell(row, 5); // Специальность
                String cabinetStr = getStringCell(row, 7); // Кабинет
                String timeStr = getStringCell(row, 8); // Время начала (HH:mm)
                String status = getStringCell(row, 9); // Статус (Свободен)

                // Проверяем, что слот свободен и дата корректна
                if (status == null || !status.contains("Свободен")) continue;
                if (dateStr == null || timeStr == null) continue;

                LocalDate date = LocalDate.parse(dateStr, DATE_FMT);

                // Пропускаем прошедшие даты
                if (date.isBefore(LocalDate.now())) continue;

                // Пытаемся найти врача в БД по ФИО (чтобы взять его ID)
                // Или извлекаем цифры из D001 -> 1
                Long doctorId = null;
                if (doctorIdStr != null && doctorIdStr.matches("D\\d+")) {
                    doctorId = Long.parseLong(doctorIdStr.substring(1));
                }

                Integer cabinet = null;
                if (cabinetStr != null && cabinetStr.matches("\\d+")) {
                    cabinet = Integer.parseInt(cabinetStr);
                }

                result.add(new ScheduleEntry(doctorId, fio, specialty, date, timeStr, cabinet));

            } catch (Exception e) {
                // Игнорируем кривые строки
            }
        }
        return result;
    }

    private String getStringCell(Row row, int col) {
        if (col < 0) return null;
        Cell cell = row.getCell(col);
        if (cell == null) return null;

        if (cell.getCellType() == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate().format(DATE_FMT);
            }
            return String.valueOf(cell.getNumericCellValue());
        }
        if (cell.getCellType() == CellType.STRING) {
            return cell.getStringCellValue().trim();
        }
        return null;
    }
}