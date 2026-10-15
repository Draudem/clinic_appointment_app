package com.clinic.backend.schedule;

import com.clinic.backend.doctor.Doctor;
import com.clinic.backend.doctor.DoctorRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Расписание из Excel-файла — вторая реализация порта {@link ScheduleProvider}.
 *
 * ФОРМАТ ФАЙЛА. Первая строка — заголовки, дальше приёмы, по одному в строке:
 * <pre>
 * ФИО | Специальность | Дата       | Время  | Кабинет
 * Иванов Пётр Сергеевич | Терапевт | 2026-11-02 | 09:00 | 214
 * </pre>
 * Заголовки распознаются по первым буквам, поэтому «ФИО/Врач» и «Дата/День»
 * работают одинаково. Дата принимается и как текст «02.11.2026»/«2026-11-02»,
 * и как настоящая дата Excel; время — «09:00», «9:00», «9.00».
 *
 * Файл перечитывается при изменении размера или времени модификации, поэтому
 * можно подложить новое расписание без перезапуска.
 *
 * Врачи сопоставляются с таблицей doctors по ФИО: запись всё равно создаётся
 * со ссылкой на врача в БД. Строки с незнакомым ФИО помечаются (doctorId=null)
 * и в диалог не попадают — записать пациента «в никуда» нельзя.
 */
public class ExcelScheduleProvider implements ScheduleProvider {

    private static final Logger log = LoggerFactory.getLogger(ExcelScheduleProvider.class);

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ISO_LOCAL_DATE,          // 2026-11-02
            DateTimeFormatter.ofPattern("dd.MM.yyyy"), // 02.11.2026
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))
                    .withResolverStyle(java.time.format.ResolverStyle.LENIENT),
    };

    private final DoctorRepository doctors;
    private final Path file;

    private volatile List<ScheduleEntry> cache = List.of();
    private volatile String cacheStamp = "";
    private volatile String lastError;

    public ExcelScheduleProvider(DoctorRepository doctors, ScheduleProperties props) {
        this.doctors = doctors;
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
            if (specialty != null && specialty.equalsIgnoreCase(e.specialty())
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
        if (fio == null) {
            return result;
        }
        for (ScheduleEntry e : entries()) {
            if (e.fio().equalsIgnoreCase(fio)
                    && !e.date().isBefore(from) && !e.date().isAfter(to)) {
                result.add(e);
            }
        }
        return result;
    }

    @Override
    public Optional<ScheduleEntry> exact(String fio, LocalDate date, String time) {
        if (fio == null || date == null || time == null) {
            return Optional.empty();
        }
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

    /** Читает файл, если он изменился с прошлого раза. */
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
            lastError = "Не удалось опросить файл расписания: " + e.getMessage();
            cache = List.of();
            return cache;
        }
        if (stamp.equals(cacheStamp)) {
            return cache;
        }
        try (InputStream in = Files.newInputStream(file);
             Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            List<ScheduleEntry> parsed = parseSheet(sheet);
            cacheStamp = stamp;
            cache = List.copyOf(parsed);
            lastError = null;
            log.info("Расписание из Excel прочитано: {} строк из {}", parsed.size(),
                    file.getFileName());
        } catch (Exception e) {
            lastError = "Файл расписания не прочитан: " + e.getMessage();
            cache = List.of();
            log.error("Ошибка чтения расписания из {}", file, e);
        }
        return cache;
    }

    private List<ScheduleEntry> parseSheet(Sheet sheet) {
        List<ScheduleEntry> result = new ArrayList<>();
        Map<String, Long> fioToId = doctorIndex();

        Row header = sheet.getRow(sheet.getFirstRowNum());
        if (header == null) {
            return result;
        }
        Cols cols = Cols.of(header);

        for (int i = header.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) {
                continue;
            }
            String fio = cols.fio == null ? null : text(row.getCell(cols.fio));
            String specialty = cols.specialty == null ? null : text(row.getCell(cols.specialty));
            LocalDate date = cols.date == null ? null : date(row.getCell(cols.date));
            LocalTime time = cols.time == null ? null : time(row.getCell(cols.time));
            Integer cabinet = cols.cabinet == null ? null : integer(row.getCell(cols.cabinet));

            if (fio == null || date == null || time == null) {
                continue;  // строка без обязательных полей — не приём
            }
            if (specialty == null) {
                // Специальность можно не указывать: возьмём её у врача из БД.
                specialty = specialtyOfFio(fioToId, fio);
            }
            Long doctorId = fioToId.get(normalizeFio(fio));
            if (doctorId == null) {
                log.warn("Строка {}: врач «{}» не найден в справочнике — приём пропущен",
                        i + 1, fio);
                continue;
            }
            result.add(new ScheduleEntry(doctorId, fio, specialty, date,
                    time.format(DateTimeFormatter.ofPattern("HH:mm")), cabinet));
        }
        return result;
    }

    /** ФИО -> id врача из БД. */
    private Map<String, Long> doctorIndex() {
        Map<String, Long> map = new HashMap<>();
        for (Doctor d : doctors.findAll()) {
            map.putIfAbsent(normalizeFio(d.getFio()), d.getId());
        }
        return map;
    }

    private String specialtyOfFio(Map<String, Long> index, String fio) {
        Long id = index.get(normalizeFio(fio));
        return id == null ? null
                : doctors.findById(id).map(Doctor::getSpecialty).orElse(null);
    }

    // --- разбор ячеек ---

    private static String text(Cell c) {
        if (c == null) {
            return null;
        }
        String v = switch (c.getCellType()) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(c)
                    ? c.getLocalDateTimeCellValue().toLocalDate().toString()
                    : trimNumber(c.getNumericCellValue());
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            case FORMULA -> c.getCellFormula();
            default -> null;
        };
        return v == null ? null : v.trim();
    }

    private static String trimNumber(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static LocalDate date(Cell c) {
        if (c == null) {
            return null;
        }
        if (c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
            LocalDateTime dt = c.getLocalDateTimeCellValue();
            return dt.toLocalDate();
        }
        String s = text(c);
        if (s == null || s.isBlank()) {
            return null;
        }
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(s, f);
            } catch (Exception ignored) {
                // пробуем следующий формат
            }
        }
        return null;
    }

    private static LocalTime time(Cell c) {
        if (c == null) {
            return null;
        }
        if (c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
            return c.getLocalDateTimeCellValue().toLocalTime();
        }
        // Время может лежать числом без формата ячейки. Тогда это либо доля суток
        // (0.364 — 08:45), либо полная дата-серийная метка Excel (46335.36).
        // Раньше такое значение уходило в text() и строка молча терялась.
        if (c.getCellType() == CellType.NUMERIC) {
            double v = c.getNumericCellValue();
            if (v > 1000) {
                return DateUtil.getLocalDateTime(v).toLocalTime();
            }
            if (v > 0 && v < 1) {
                return LocalTime.ofSecondOfDay(Math.round(v * 86400));
            }
        }
        String s = text(c);
        if (s == null || s.isBlank()) {
            return null;
        }
        String[] parts = s.trim().replace(' ', ':').replace('.', ':').split(":");
        try {
            if (parts.length == 2) {
                return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            }
            if (parts.length == 3) {
                return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]));
            }
        } catch (Exception ignored) {
            // не разобрали
        }
        return null;
    }

    private static Integer integer(Cell c) {
        String s = text(c);
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalizeFio(String fio) {
        return fio.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Номера колонок, найденные по заголовкам. */
    private record Cols(Integer fio, Integer specialty, Integer date, Integer time, Integer cabinet) {

        static Cols of(Row header) {
            Integer fio = null, specialty = null, date = null, time = null, cabinet = null;
            for (Cell c : header) {
                String h = text(c);
                if (h == null || h.isBlank()) {
                    continue;
                }
                String norm = h.toLowerCase(Locale.ROOT).replace(" ", "");
                int col = c.getColumnIndex();
                if (norm.startsWith("фио") || norm.startsWith("врач")) {
                    fio = col;
                } else if (norm.startsWith("спец")) {
                    specialty = col;
                } else if (norm.startsWith("дата") || norm.startsWith("день")) {
                    date = col;
                } else if (norm.startsWith("время") || norm.startsWith("час")) {
                    time = col;
                } else if (norm.startsWith("кабин")) {
                    cabinet = col;
                }
            }
            // Заголовков нет — берём колонки по порядку как в шаблоне.
            if (fio == null && header.getLastCellNum() >= 5) {
                fio = 0;
                specialty = 1;
                date = 2;
                time = 3;
                cabinet = 4;
            }
            return new Cols(fio, specialty, date, time, cabinet);
        }
    }

    /** Имя файла для сообщений об ошибке. */
    public String describeFile() {
        return file.getFileName() == null ? file.toString() : file.getFileName().toString();
    }

    /** Колонка Excel -> буква (для понятных сообщений). */
    static String columnName(int index) {
        return CellReference.convertNumToColString(index);
    }
}