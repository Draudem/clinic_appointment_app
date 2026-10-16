package com.clinic.backend.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Разбор даты рождения вслух.
 *
 * <p>Повод для теста — реальный звонок: пациент назвал ФИО, потом назвал дату
 * рождения словами, и сценарий снова спросил дату рождения. Раз за разом,
 * пока лестница Р-05 не увела бы его оператору. Причина была в разборе, а не
 * в пациенте и не в распознавании речи: год, названный словами, не читался.
 *
 * <p>Вторая проверка важнее первой. Неверно разобранная дата рождения хуже,
 * чем неразобранная: по «12 сентября» вместо «12 марта» пациент может быть
 * опознан как другой человек, а это уже персональные данные по 152-ФЗ.
 */
class PatientNameParserBirthDateTest {

    @Test
    @DisplayName("год словами через «тысяча» — самый естественный способ сказать вслух")
    void fullYearSpoken() {
        assertEquals(LocalDate.of(1978, 3, 12),
                PatientNameParser.parse("двенадцатое марта тысяча девятьсот семьдесят восьмого")
                        .birthDate());
        assertEquals(LocalDate.of(1985, 12, 31),
                PatientNameParser.parse("тридцать первое декабря тысяча девятьсот восемьдесят пятый")
                        .birthDate());
        assertEquals(LocalDate.of(2005, 1, 1),
                PatientNameParser.parse("первое января две тысячи пятого").birthDate());
    }

    @Test
    @DisplayName("год двумя словами и цифрами")
    void shortYearSpoken() {
        assertEquals(LocalDate.of(1978, 3, 12),
                PatientNameParser.parse("двенадцатое марта семьдесят восьмого").birthDate());
        assertEquals(LocalDate.of(1978, 3, 12),
                PatientNameParser.parse("двенадцатое марта 1978").birthDate());
        assertEquals(LocalDate.of(1978, 3, 12),
                PatientNameParser.parse("двенадцатое марта 1978 года").birthDate());
    }

    @Test
    @DisplayName("месяц числом — так пациент диктует «12.03.78»")
    void monthAsNumber() {
        assertEquals(LocalDate.of(1978, 3, 12),
                PatientNameParser.parse("двенадцатое три семьдесят восемь").birthDate());
    }

    @Test
    @DisplayName("месяц и день в обратном порядке не дают чужой даты")
    void monthBeforeDay() {
        // раньше фраза разбиралась как 12 СЕНТЯБРЯ — молчаливо неверная дата
        assertEquals(LocalDate.of(1978, 3, 12),
                PatientNameParser.parse("марта двенадцатое тысяча девятьсот семьдесят восьмого")
                        .birthDate());
    }

    @Test
    @DisplayName("реплика «Смирнова Ольга Викторовна, двенадцатое марта тысяча девятьсот…»")
    void wholeIdentityPhrase() {
        // Это ровно то условие, на котором зацикливался сценарий: BookingFlow
        // спрашивает ФИО, потом дату рождения, и только когда обе части найдены
        // (data.hasFio() && data.hasBirthDate()) идёт поиск пациента.
        var d = PatientNameParser.parse(
                "Смирнова Ольга Викторовна, двенадцатое марта тысяча девятьсот семьдесят восьмого");
        assertEquals("Смирнова Ольга Викторовна", d.fio());
        assertEquals(LocalDate.of(1978, 3, 12), d.birthDate());
        assertEquals("Смирнова", d.surname());
    }

    @Test
    @DisplayName("разбор даты из реального звонка: Ганеев, шестое января")
    void realCallPhrase() {
        var d = PatientNameParser.parse(
                "Ганеев Тимур Айратович, шестое января тысяча девятьсот девяностого");
        assertEquals(LocalDate.of(1990, 1, 6), d.birthDate());
    }

    @Test
    @DisplayName("в фразе без даты ничего не выдумывается")
    void noDateIsNotGuessed() {
        assertNull(PatientNameParser.parse("Смирнова Ольга Викторовна").birthDate());
        assertNull(PatientNameParser.parse("хочу записаться к стоматологу").birthDate());
    }

    @Test
    @DisplayName("дата не из записи пациента")
    void appointmentDateIsNotBirthDate() {
        // «запишите на двадцатое октября» — это не день рождения
        assertNull(PatientNameParser.parse("запишите меня двадцатого октября").birthDate());
    }
}
