package com.clinic.backend.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор ФИО и даты рождения из речи.
 *
 * Здесь нет нейросети — и это осознанное решение (см. обоснование в
 * PatientNameParser). Тесты фиксируют то, что реально приходит из
 * распознавания: текст СТРОЧНЫМИ, с ошибками и лишними словами.
 */
class PatientNameParserTest {

    @ParameterizedTest(name = "«{0}» -> {1}")
    @DisplayName("ФИО понимается независимо от регистра — распознавание даёт строчные")
    @CsvSource({
            "Смирнова Ольга Викторовна,   Смирнова Ольга Викторовна",
            "смирнова ольга викторовна,   Смирнова Ольга Викторовна",
            "СМИРНОВА ОЛЬГА ВИКТОРОВНА,   Смирнова Ольга Викторовна",
            "Меня зовут Смирнова Ольга Викторовна,   Смирнова Ольга Викторовна",
            "моя фамилия Смирнова Ольга Викторовна,   Смирнова Ольга Викторовна",
            "Петров Игорь Николаевич,   Петров Игорь Николаевич",
            "петров игорь николаевич,   Петров Игорь Николаевич",
            "Кузьмина Ольга,   Кузьмина Ольга",
            "кузьмина ольга,   Кузьмина Ольга"
    })
    void fioAnyCase(String input, String expected) {
        assertEquals(expected, PatientNameParser.parse(input).fio());
    }

    @ParameterizedTest(name = "«{0}» -> ФИО пустое")
    @DisplayName("служебные слова и названия специальностей не становятся фамилией")
    @CsvSource({
            "хочу записаться к стоматологу",
            "хочу к лору",
            "нужен талон к терапевту",
            "запишите к окулисту",
            "хочу к неврологу",
            "да",
            "завтра в 15:00"
    })
    void noFioInServiceWords(String input) {
        assertNull(PatientNameParser.parse(input).fio(),
                "в «" + input + "» не должно быть ФИО");
    }

    @Test
    @DisplayName("дата рождения словами и цифрами")
    void birthDate() {
        assertEquals(LocalDate.of(1978, 3, 12), PatientNameParser
                .parse("Смирнова Ольга Викторовна, двенадцатое марта семьдесят восьмого")
                .birthDate());
        assertEquals(LocalDate.of(1962, 5, 5), PatientNameParser
                .parse("Петров Игорь Николаевич, 05.05.1962")
                .birthDate());
        // «12.03.78» словами, без названия месяца
        assertEquals(LocalDate.of(1978, 3, 12), PatientNameParser
                .parse("Соколов, двенадцатое три семьдесят восемь")
                .birthDate());
        // «двенадцатое ноль три семьдесят восемь»
        assertEquals(LocalDate.of(1978, 3, 12), PatientNameParser
                .parse("Соколов, двенадцатое ноль три семьдесят восемь")
                .birthDate());
        assertEquals(LocalDate.of(2026, 1, 12), PatientNameParser
                .parse("Соколов, двенадцатое января двадцать шестого")
                .birthDate());
    }

    @Test
    @DisplayName("двузначный год не превращается в 1970 или 2078")
    void twoDigitYear() {
        // Пациенту не может быть больше 110 лет (Приложение А.3 регламента)
        assertEquals(1978, PatientNameParser.normalizeYear(78));
        assertEquals(2026, PatientNameParser.normalizeYear(26));
        assertNotEquals(2078, PatientNameParser.normalizeYear(78));
        assertNotEquals(1970, PatientNameParser.normalizeYear(78));
    }

    @Test
    @DisplayName("номер полиса ОМС")
    void policy() {
        assertEquals("2203123456789012",
                PatientNameParser.parse("мой полис 2203 12 34 56 78 90 12").policyNo());
        assertEquals("2203123456789012",
                PatientNameParser.parse("полис 2203123456789012").policyNo());
        assertNull(PatientNameParser.parse("запишите к стоматологу").policyNo());
    }

    @ParameterizedTest(name = "одно слово -> фамилия «{0}», но не ФИО")
    @DisplayName("одно названное слово принимается как фамилия, а не как ФИО")
    @CsvSource({"Кузьмина", "кузьмина", "Соколов"})
    void singleWordIsSurnameOnly(String input) {
        var d = PatientNameParser.parse(input);
        assertNull(d.fio(), "одного слова недостаточно для ФИО");
        assertTrue(d.hasSurname(), "но как кандидат в фамилию слово годится");
    }

    @Test
    @DisplayName("полное имя пациента не теряется целиком")
    void fullFioKeepsThreeParts() {
        var d = PatientNameParser.parse("смирнова ольга викторовна двенадцатое марта семьдесят восьмого");
        assertEquals(3, d.fio().split(" ").length, "должны быть фамилия, имя и отчество");
        assertTrue(d.hasFio() && d.hasBirthDate());
    }
}