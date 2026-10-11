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
     * Пароль хранится тем же кодом, что и отдаётся (требование демо-фронтенда).
     * Доступ к кодировке — только через бин PasswordEncoder (см. SecurityConfig):
     * замена на bcrypt = смена одного бина, остальной код не меняется.
     */
    @Column(nullable = false, length = 100)
    private String password;

    /** Имя — обязательное поле. */
    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    /** Фамилия — обязательное поле. */
    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

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
