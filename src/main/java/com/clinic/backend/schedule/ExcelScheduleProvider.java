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
        if (!Files.isReadable(file)) {
            return new Health(false, name(), 0, "Файл не найден: " + file.toAbsolutePath());
        }
        // НЕ читаем Excel целиком: entries() разворачивает 42k строк, POI держит
        // их в памяти (~1.5 ГБ heap). На VPS с -Xmx2g это лишний риск OOM,
        // а Health нужен только для лога при старте.
        return new Health(true, name(), -1, null);   // -1 = «не считали»
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

            // Ищем вкладку "Слоты", если ее нет — берем первую
            Sheet sheet = wb.getSheet("Слоты");
            if (sheet == null) {
                sheet = wb.getSheetAt(0);
                log.warn("Вкладка 'Слоты' не найдена, читаем первую: {}", wb.getSheetName(0));
            } else {
                log.info("Читаем вкладку: 'Слоты'");
            }

            List<ScheduleEntry> parsed = parseSheet(sheet);

            cacheStamp = stamp;
            cache = List.copyOf(parsed);
            lastError = null;

            // Добавляем лог для проверки
            log.info("Расписание из Excel успешно прочитано. Найдено слотов: {}", parsed.size());

        } catch (Exception e) {
            lastError = "Ошибка чтения: " + e.getMessage();
            cache = List.of();
            log.error("Ошибка чтения расписания из {}", file, e);
        }
        return cache;
    }

    private List<ScheduleEntry> parseSheet(Sheet sheet) {
        List<ScheduleEntry> result = new ArrayList<>();

        // Пропускаем заголовки (первая строка)
        int startRow = 1;

        for (int i = startRow; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;

            try {
                // Колонки согласно скриншоту:
                // 0 - ID, 1 - Дата, 2 - День, 3 - ID врача, 4 - ФИО, 5 - Специальность, 6 - Отделение, 7 - Кабинет, 8 - Время начала, 11 - Статус
                String dateStr = getStringCell(row, 1);
                String doctorIdStr = getStringCell(row, 3);
                String fio = getStringCell(row, 4);
                String specialty = getStringCell(row, 5);
                String cabinetStr = getStringCell(row, 7);
                String timeStr = getStringCell(row, 8); // Начало (например, 13:20)
                String status = getStringCell(row, 11);  // Статус (например, Свободен)

                // Ищем только свободные слоты
                if (status == null || !status.toLowerCase().contains("свободен")) continue;
                if (dateStr == null || timeStr == null) continue;

                LocalDate date = LocalDate.parse(dateStr, DATE_FMT);

                // Не берем прошедшие даты
                if (date.isBefore(LocalDate.now())) continue;

                Long doctorId = null;
                if (doctorIdStr != null && doctorIdStr.matches("[Dd]\\d+")) {
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
        log.info("Распарсено слотов из Excel: {}", result.size());
        return result;
    }

    private String getStringCell(Row row, int col) {
        if (col < 0) return null;
        Cell cell = row.getCell(col);
        if (cell == null) return null;

        if (cell.getCellType() == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                // Если ячейка отформатирована как дата, извлекаем только время (если это колонка времени)
                // или только дату (если это колонка даты).
                // Но проще: возвращаем отформатированную строку
                java.time.LocalDateTime dateTime = cell.getLocalDateTimeCellValue();

                // Если время 00:00, значит это просто дата
                if (dateTime.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)) {
                    return dateTime.toLocalDate().format(DATE_FMT);
                }
                // Иначе возвращаем время в формате HH:mm
                return dateTime.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            }
            return String.valueOf(cell.getNumericCellValue());
        }
        if (cell.getCellType() == CellType.STRING) {
            return cell.getStringCellValue().trim();
        }
        return null;
    }
}