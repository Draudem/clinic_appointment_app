package com.clinic.backend.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByLogin(String login);

    boolean existsByLogin(String login);

    /** Сколько пользователей с ролью — нужно, чтобы не удалить последнего администратора. */
    long countByRole(Role role);

    // --- поиск пациента для идентификации по телефону (регламент 3.5) ---

    List<User> findByLastNameIgnoreCase(String lastName);

    Optional<User> findByPhone(String phone);

    /**
     * Поиск по ФИО и дате рождения. Фамилия, имя и отчество сравниваются
     * без учёта регистра; отчество может быть не заполнено, поэтому оно
     * либо совпадает, либо в базе пустое.
     *
     * Если вернулось несколько записей — пациент не идентифицирован
     * (регламент 3.5: не выбирать наугад), вызывающий код переводит на оператора.
     */
    @Query("""
            select u from User u
             where upper(u.lastName) = upper(:last)
               and upper(u.firstName) = upper(:first)
               and (u.patronymic is null or u.patronymic = '' or upper(u.patronymic) = upper(:patronymic))
               and u.birthDate = :birth
            """)
    List<User> findForIdentification(@Param("last") String last,
                                    @Param("first") String first,
                                    @Param("patronymic") String patronymic,
                                    @Param("birth") LocalDate birth);

    /**
     * То же, но без даты рождения — когда пациент её ещё не назвал.
     *
     * Отдельный метод, а не «:birth is null» в одном запросе: PostgreSQL не может
     * определить тип null-параметра и падает с «could not determine data type».
     */
    @Query("""
            select u from User u
             where upper(u.lastName) = upper(:last)
               and upper(u.firstName) = upper(:first)
               and (u.patronymic is null or u.patronymic = '' or upper(u.patronymic) = upper(:patronymic))
            """)
    List<User> findForIdentificationWithoutBirth(@Param("last") String last,
                                                @Param("first") String first,
                                                @Param("patronymic") String patronymic);
}