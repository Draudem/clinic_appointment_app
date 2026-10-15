package com.clinic.backend.support;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Генератор тестового файла расписания Excel.
 *
 * Нужен, чтобы проверить вторую реализацию порта ScheduleProvider — «расписание
 * из Excel» — без участия коллеги, который пришлёт настоящий файл.
 *
 * Запуск: ./gradlew run -PmainClass=com.clinic.backend.support.ScheduleExcelGenerator
 * или из тестов/IDE main().
 */
public final class ScheduleExcelGenerator {

    private ScheduleExcelGenerator() {
    }

    /** Путь, который прописан в application.yml по умолчанию. */
    public static final Path DEFAULT_PATH = Path.of("data/schedule.xlsx");

    /**
     * Строки-шаблоны. Специальности и ФИО совпадают с тем, что заносит
     * DataSeeder, — иначе строки не сопоставятся с врачами в БД и будут пропущены.
     */
    public static void write(Path path, LocalDate from) {
        LocalDate start = from == null ? LocalDate.now() : from;
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Расписание");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("ФИО");
            header.createCell(1).setCellValue("Специальность");
            header.createCell(2).setCellValue("Дата");
            header.createCell(3).setCellValue("Время");
            header.createCell(4).setCellValue("Кабинет");

            int row = 1;
            // Стоматолог: будни, три приёма в день
            for (LocalDate d = start; d.isBefore(start.plusDays(14)); d = d.plusDays(1)) {
                if (isWeekday(d)) {
                    row = addRow(sheet, row, "Смирнов Алексей Викторович", "Стоматолог",
                            d, "09:00", 201);
                    row = addRow(sheet, row, "Смирнов Алексей Викторович", "Стоматолог",
                            d, "11:00", 201);
                    row = addRow(sheet, row, "Смирнов Алексей Викторович", "Стоматолог",
                            d, "15:00", 201);
                }
            }
            // Терапевты по участкам
            row = addRow(sheet, row, "Иванов Пётр Сергеевич", "Терапевт", start, "09:00", 214);
            row = addRow(sheet, row, "Попова Елена Игоревна", "Терапевт",
                    start.plusDays(1), "10:30", 215);
            // ЛОР — чтобы сработало «лучше не в пятницу»
            LocalDate friday = nextFriday(start);
            row = addRow(sheet, row, "Морозова Ольга Ивановна", "ЛОР", friday, "15:30", 301);
            row = addRow(sheet, row, "Морозова Ольга Ивановна", "ЛОР",
                    friday.plusDays(2), "15:30", 301);

            sheet.setColumnWidth(0, 34 * 256);
            sheet.setColumnWidth(1, 18 * 256);
            sheet.setColumnWidth(2, 14 * 256);
            sheet.setColumnWidth(3, 10 * 256);
            sheet.setColumnWidth(4, 10 * 256);

            Files.createDirectories(path.toAbsolutePath().getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                wb.write(out);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось создать тестовое расписание " + path, e);
        }
    }

    private static int addRow(Sheet sheet, int rowIndex, String fio, String specialty,
                              LocalDate date, String time, int cabinet) {
        Row r = sheet.createRow(rowIndex);
        r.createCell(0).setCellValue(fio);
        r.createCell(1).setCellValue(specialty);
        // Дата кладём текстом в двух форматах сразу — так проверим оба разбора
        r.createCell(2).setCellValue(date.toString());
        r.createCell(3).setCellValue(time);
        Cell c = r.createCell(4);
        c.setCellValue(cabinet);
        return rowIndex + 1;
    }

    private static boolean isWeekday(LocalDate d) {
        return d.getDayOfWeek().getValue() <= 5;
    }

    private static LocalDate nextFriday(LocalDate from) {
        LocalDate d = from;
        while (d.getDayOfWeek() != java.time.DayOfWeek.FRIDAY) {
            d = d.plusDays(1);
        }
        return d;
    }

    /** Контрольная проверка чтения времени как числа, а не текста. */
    public static void writeNumericTime(Path path, LocalDate date, LocalTime time) {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Расписание");
            Row header = sheet.createRow(0);
            String[] names = {"ФИО", "Специальность", "Дата", "Время", "Кабинет"};
            for (int i = 0; i < names.length; i++) {
                header.createCell(i).setCellValue(names[i]);
            }
            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue("Смирнов Алексей Викторович");
            r.createCell(1).setCellValue("Стоматолог");
            r.createCell(2).setCellValue(date.toString());
            // время как настоящее время Excel
            r.createCell(3).setCellValue(java.util.Date.from(date.atTime(time)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant()));
            // Настоящий формат времени Excel — так файл выглядит у живых людей
            org.apache.poi.ss.usermodel.CellStyle timeStyle = wb.createCellStyle();
            timeStyle.setDataFormat(wb.getCreationHelper()
                    .createDataFormat().getFormat("hh:mm"));
            r.getCell(3).setCellStyle(timeStyle);
            r.createCell(4).setCellValue(201);
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                wb.write(out);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось создать тестовое расписание " + path, e);
        }
    }

    public static void main(String[] args) {
        Path path = args.length > 0 ? Path.of(args[0]) : DEFAULT_PATH;
        write(path, LocalDate.now());
        System.out.println("Тестовое расписание записано: " + path.toAbsolutePath());
    }
}