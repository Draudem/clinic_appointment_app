package com.clinic.backend.user;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String login;

    /**
     * Пароль в API наружу не отдаётся: /api/users возвращает UserDto без этого поля.
     * Хранится он тем же кодом, что и отдавался раньше (см. SecurityConfig).
     * Доступ к кодировке — только через бин PasswordEncoder: замена на bcrypt =
     * смена одного бина, остальной код не меняется.
     */
    @Column(nullable = false, length = 100)
    private String password;

    /** Имя — обязательное поле. */
    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    /** Фамилия — обязательное поле. */
    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    /**
     * Отчество. Регламент (раздел 3.5) требует идентификацию пациента по
     * ФИО и дате рождения: без отчества поиск среди однофамильцев ненадёжен.
     */
    @Column(name = "patronymic", length = 100)
    private String patronymic;

    /**
     * Дата рождения. Второй обязательный элемент идентификации.
     * Поле новое и nullable: старые записи и демо-пользователи заполняются
     * постепенно, поиск по дате рождения просто не срабатывает, пока её нет.
     */
    @Column(name = "birth_date")
    private java.time.LocalDate birthDate;

    /**
     * Телефон пациента — ANI с телефонной станции. Используется как
     * дополнительный признак при поиске, но не как единственный: номер
     * может быть чужим, и доверять ему нельзя (регламент 3.5).
     */
    @Column(name = "phone", length = 30)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    protected User() {
        // for JPA
    }

    public User(String login, String password, Role role, String firstName, String lastName) {
        this.login = login;
        this.password = password;
        this.role = role;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    public Long getId() {
        return id;
    }

    public String getLogin() {
        return login;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getPatronymic() {
        return patronymic;
    }

    public void setPatronymic(String patronymic) {
        this.patronymic = patronymic;
    }

    public java.time.LocalDate getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(java.time.LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    /** ФИО одной строкой — как просит произнести регистратура. */
    public String getFio() {
        String s = String.join(" ",
                java.util.stream.Stream.of(lastName, firstName, patronymic)
                        .filter(x -> x != null && !x.isBlank())
                        .toList());
        return s.isBlank() ? login : s;
    }

    /** Обращение по имени-отчеству — только ПОСЛЕ идентификации (регламент 3.5). */
    public String getAddressName() {
        StringBuilder sb = new StringBuilder();
        if (firstName != null && !firstName.isBlank()) {
            sb.append(firstName);
        }
        if (patronymic != null && !patronymic.isBlank()) {
            sb.append(sb.length() > 0 ? " " : "").append(patronymic);
        }
        return sb.toString();
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }
}
