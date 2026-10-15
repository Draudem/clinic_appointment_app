package com.clinic.backend.identity;

import com.clinic.backend.user.User;
import com.clinic.backend.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Поиск пациента в таблице users.
 *
 * Строго по регламенту 3.5:
 *   - первым данные называет пациент, ассистент ничего не подсказывает;
 *   - номер телефона — только дополнительный признак, никогда единственный;
 *   - если найдено больше одного человека — личность НЕ установлена,
 *     вызывающий код переводит на оператора (R-07), а не выбирает первого.
 */
@Component
public class DbPatientIdentityResolver implements PatientIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(DbPatientIdentityResolver.class);

    private final UserRepository users;

    public DbPatientIdentityResolver(UserRepository users) {
        this.users = users;
    }

    @Override
    public String name() {
        return "db:users";
    }

    @Override
    public Optional<PatientIdentity> findByLogin(String login) {
        if (login == null || login.isBlank()) {
            return Optional.empty();
        }
        return users.findByLogin(login).map(u -> toIdentity(u, "http-login"));
    }

    @Override
    public Optional<PatientIdentity> findByFioAndBirthDate(String fio, LocalDate birthDate) {
        String[] parts = splitFio(fio);
        if (parts.length < 2) {
            return Optional.empty();
        }
        String patronymic = parts.length > 2 ? parts[2] : null;
        var found = birthDate == null
                ? users.findForIdentificationWithoutBirth(parts[0], parts[1], patronymic)
                : users.findForIdentification(parts[0], parts[1], patronymic, birthDate);
        if (found.size() != 1) {
            if (found.size() > 1) {
                // Разнофамильцы с одинаковым именем и датой рождения: выбирать нельзя.
                log.warn("Идентификация неоднозначна: {} кандидатов по «{}» {}",
                        found.size(), fio, birthDate);
            }
            return Optional.empty();
        }
        return Optional.of(toIdentity(found.get(0), "fio-dob"));
    }

    @Override
    public Optional<PatientIdentity> findByPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return Optional.empty();
        }
        return users.findByPhone(phone).map(u -> toIdentity(u, "ani"));
    }

    @Override
    public Optional<PatientIdentity> findByLastName(String lastName) {
        if (lastName == null || lastName.isBlank()) {
            return Optional.empty();
        }
        var found = users.findByLastNameIgnoreCase(lastName);
        return found.size() == 1 ? Optional.of(toIdentity(found.get(0), "last-name")) : Optional.empty();
    }

    /** ФИО -> [фамилия, имя, отчество]; пустые отчество пропускаем. */
    static String[] splitFio(String fio) {
        if (fio == null) {
            return new String[0];
        }
        return java.util.Arrays.stream(fio.trim().split("[\\s,]+"))
                .filter(s -> !s.isBlank())
                .toArray(String[]::new);
    }

    static PatientIdentity toIdentity(User u, String source) {
        return new PatientIdentity(u.getId(), u.getLogin(), u.getLastName(), u.getFirstName(),
                u.getPatronymic(), u.getBirthDate(), u.getPhone(), source);
    }
}