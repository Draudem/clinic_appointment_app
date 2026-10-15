package com.clinic.backend.identity;

import java.time.LocalDate;

/**
 * Кто говорит по телефону.
 *
 * До идентификации у ассистента есть только то, что пациент назвал сам.
 * Всё, что ассистент произносит в ответ, берётся отсюда — и только после
 * успешного поиска (регламент, раздел 3.5: «пациент называет первым»).
 *
 * @param userId     id в БД; null, если личность не установлена
 * @param login      логин для записи в БД
 * @param last       фамилия
 * @param first      имя
 * @param patronymic отчество
 * @param birthDate  дата рождения
 * @param phone      телефон
 * @param source     откуда получено: http-login | fio-dob | ani
 */
public record PatientIdentity(Long userId, String login, String last, String first,
                              String patronymic, LocalDate birthDate, String phone,
                              String source) {

    /** Идентификация не удалась — записывать нельзя. */
    public boolean identified() {
        return userId != null && login != null;
    }

    public String fio() {
        String s = String.join(" ",
                java.util.stream.Stream.of(last, first, patronymic)
                        .filter(x -> x != null && !x.isBlank()).toList());
        return s.isBlank() ? null : s;
    }

    /** Обращение «Ольга Викторовна»; до идентификации пустое — называть нечего. */
    public String addressName() {
        StringBuilder sb = new StringBuilder();
        if (first != null && !first.isBlank()) {
            sb.append(first);
        }
        if (patronymic != null && !patronymic.isBlank()) {
            sb.append(sb.length() > 0 ? " " : "").append(patronymic);
        }
        return sb.toString();
    }

    /** Ничего не найдено. */
    public static PatientIdentity unknown() {
        return new PatientIdentity(null, null, null, null, null, null, null, "none");
    }

    /**
     * Копия с известным логином.
     *
     * Нужна для HTTP-входа: пациент аутентифицирован, но ФИО и дату рождения
     * сценарию нужны для обращения по имени (регламент 3.5) — а получаем мы их
     * из реестра уже по логину, а не от пациента.
     */
    public PatientIdentity withLogin(String knownLogin) {
        return new PatientIdentity(userId, knownLogin, last, first, patronymic,
                birthDate, phone, source + "+login");
    }
}