package com.clinic.backend.voice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Русские даты и время: разбор и обратная озвучка.
 *
 * Это самые «тихие» места проекта: ошибка здесь не видна в логах, а пациент
 * просто получает не ту дату или слышит «2026-11-02 09:00» вместо
 * «в понедельник, второго ноября, в девять».
 */
class RussianTimeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);   // среда

    @Test
    @DisplayName("число прописью — для озвучки времени и даты")
    void numberWords() {
        assertEquals("ноль", RussianTime.number(0));
        assertEquals("девять", RussianTime.number(9));
        assertEquals("пятнадцать", RussianTime.number(15));
        assertEquals("двадцать", RussianTime.number(20));
        assertEquals("двадцать один", RussianTime.number(21));
        assertEquals("девяносто девять", RussianTime.number(99));
        assertEquals("сто тридцать", RussianTime.number(130));
    }

    @Test
    @DisplayName("время озвучивается словами, как требует регламент 3.2")
    void speakTime() {
        assertEquals("в девять тридцать", RussianTime.sayTime("09:30"));
        assertEquals("в восемь сорок пять", RussianTime.sayTime("08:45"));
        assertEquals("в пятнадцать пятнадцать", RussianTime.sayTime("15:15"));
        assertEquals("в одиннадцать", RussianTime.sayTime("11:00"));
    }

    @Test
    @DisplayName("дата озвучивается с днём недели: «в четверг, первого октября»")
    void speakDate() {
        // 2026-10-01 — четверг
        assertEquals("в четверг, первого октября", RussianTime.sayDate(LocalDate.of(2026, 10, 1)));
        assertEquals("в воскресенье, пятнадцатого марта", RussianTime.sayDate(LocalDate.of(2026, 3, 15)));
        assertEquals("в воскресенье, тридцать первого мая", RussianTime.sayDate(LocalDate.of(2026, 5, 31)));
    }

    @Test
    @DisplayName("разбор точного времени цифрами и словами")
    void parseTime() {
        assertEquals(LocalTime.of(15, 0), RussianTime.parseHhmm("15:00"));
        assertEquals(LocalTime.of(9, 45), RussianTime.parseHhmm("9.45"));
        assertNull(RussianTime.parseHhmm("завтра"));

        assertEquals(LocalTime.of(16, 0), RussianTime.parseWordsTime("в четыре часа дня"));
        assertEquals(LocalTime.of(9, 30), RussianTime.parseWordsTime("в девять тридцать"));
        assertEquals(LocalTime.of(17, 0), RussianTime.parseWordsTime("в пять вечера"));
        assertEquals(LocalTime.of(9, 0), RussianTime.parseWordsTime("в девять утра"));
    }

    @Test
    @DisplayName("дата в реплике не должна читаться как время")
    void dateDoesNotLeakIntoTime() {
        // Регрессия: «на 16 октября в четыре часа дня» читалась как 04:04,
        // потому что «16» и «четыре» попадали в разбор времени.
        assertEquals(LocalTime.of(16, 0),
                RussianTime.parseWordsTime("на 16 октября в четыре часа дня"));
        assertEquals(LocalTime.of(9, 0),
                RussianTime.parseWordsTime("завтра в девять утра"));
    }

    @Test
    @DisplayName("разбор абсолютной даты — то, чего не умеет модель")
    void parseAbsoluteDate() {
        assertEquals(LocalDate.of(2026, 10, 1),
                RussianTime.parseDayMonth("на первое октября", TODAY));
        assertEquals(LocalDate.of(2026, 10, 16),
                RussianTime.parseDayMonth("хочу на 16 октября", TODAY));
        assertEquals(LocalDate.of(2026, 11, 2),
                RussianTime.parseDayMonth("запишите на второе ноября", TODAY));
    }

    @Test
    @DisplayName("TimeResolver понимает и текст, и токены модели")
    void resolvePrefersText() {
        // текст: абсолютная дата + словесное время
        var p = TimeResolver.resolve(java.util.List.of(), "на 16 октября в четыре часа дня", TODAY);
        assertEquals(LocalDate.of(2026, 10, 16), p.date());
        assertEquals("16:00", p.exact());

        // модель: относительный день + точный слот из новой сетки
        var q = TimeResolver.resolve(java.util.List.of("T_TOMORROW", "T_H09_45"), null, TODAY);
        assertEquals(LocalDate.of(2026, 10, 15), q.date());
        assertEquals("09:45", q.exact());

        // часть суток -> окно приёма
        var r = TimeResolver.resolve(java.util.List.of("T_AFTER_NOON"), null, TODAY);
        assertEquals("12:00", r.earliest());
        assertEquals("16:00", r.latest());
    }

    @Test
    @DisplayName("отвергнутый день недели попадает в пожелание")
    void excludeWeekday() {
        var p = TimeResolver.resolve(java.util.List.of(), "к лору, но лучше не в пятницу", TODAY);
        assertEquals(java.time.DayOfWeek.FRIDAY, p.excludeDay());
    }

    @Test
    @DisplayName("пустая реплика не превращается в сегодняшний слот")
    void nothingSaid() {
        var p = TimeResolver.resolve(java.util.List.of(), "", TODAY);
        assertTrue(p.isEmpty());
    }
}