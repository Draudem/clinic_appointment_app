package com.clinic.assistant.user;

/**
 * Роли пользователей. В БД хранятся коды ADMIN/PATIENT,
 * наружу (фронтенду) отдаются русские названия: администратор / пациент.
 */
public enum Role {
    ADMIN("администратор"),
    PATIENT("пациент");

    private final String title;

    Role(String title) {
        this.title = title;
    }

    /** Название роли для отображения в UI (фронтенд сравнивает строку «администратор»). */
    public String getTitle() {
        return title;
    }
}
