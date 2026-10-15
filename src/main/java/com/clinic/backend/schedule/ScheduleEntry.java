package com.clinic.backend.schedule;

import java.time.LocalDate;

/**
 * Одна строка расписания: кто принимает, когда и в каком кабинете.
 *
 * Не зависит от источника данных: одинаково приходит из таблицы doctors
 * и из Excel-файла. Всё, что ниже по стеку (подбор слота, голосовой сценарий),
 * работает только с этим типом — поэтому смена источника не трогает остальные этапы.
 *
 * @param doctorId   id врача в БД; null, если источник не смог сопоставить ФИО с врачом
 * @param fio        ФИО врача как в справочнике
 * @param specialty  специальность
 * @param date       дата приёма
 * @param time       время приёма HH:mm
 * @param cabinet    кабинет; может быть null
 */
public record ScheduleEntry(Long doctorId, String fio, String specialty,
                            LocalDate date, String time, Integer cabinet) {

    /** Ключ слота в формате, общем для подбора и брони: d{id}-{дата}-{время}. */
    public String slotKey() {
        return "d" + doctorId + "-" + date + "-" + time.replace(":", "");
    }
}