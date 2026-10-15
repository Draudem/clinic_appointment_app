package com.clinic.backend.schedule;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки источника расписания.
 *
 * {@code app.schedule.source} — переключатель реализации порта:
 *   db    (по умолчанию) — расписание из таблицы doctors;
 *   excel — расписание из файла {@code app.schedule.excel.path}.
 *
 * Меняется одним флагом без правок кода, поэтому «расписание из Excel»
 * подключается одной настройкой, а не новой разработкой.
 */
@ConfigurationProperties(prefix = "app.schedule")
public class ScheduleProperties {

    /** Источник расписания: db | excel. */
    private String source = "db";

    private final Excel excel = new Excel();

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Excel getExcel() {
        return excel;
    }

    public static class Excel {

        /** Путь к .xlsx с расписанием. */
        private String path = "data/schedule.xlsx";

        /** Имя листа; если пусто — берётся первый. */
        private String sheet = "";

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getSheet() {
            return sheet;
        }

        public void setSheet(String sheet) {
            this.sheet = sheet;
        }
    }
}