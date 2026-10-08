-- Схема клиники: пользователи, роли, врачи, расписание, записи, голосовые запросы

CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    login         VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(16)  NOT NULL CHECK (role IN ('ADMIN', 'PATIENT')),
    full_name     VARCHAR(128),
    email         VARCHAR(128),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE specialty (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL UNIQUE
);

CREATE TABLE specialty_disease (
    specialty_id BIGINT      NOT NULL REFERENCES specialty (id) ON DELETE CASCADE,
    disease      VARCHAR(128) NOT NULL,
    PRIMARY KEY (specialty_id, disease)
);

CREATE TABLE doctor (
    id           BIGSERIAL PRIMARY KEY,
    fio          VARCHAR(128) NOT NULL,
    specialty_id BIGINT       NOT NULL REFERENCES specialty (id),
    active       BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_doctor_specialty ON doctor (specialty_id);

-- Еженедельное расписание врача: weekday по ISO (1 = Пн ... 7 = Вс)
CREATE TABLE schedule_slot (
    id               BIGSERIAL PRIMARY KEY,
    doctor_id        BIGINT      NOT NULL REFERENCES doctor (id) ON DELETE CASCADE,
    weekday          INTEGER     NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    start_time       TIME        NOT NULL,
    duration_minutes INTEGER     NOT NULL DEFAULT 30
);

CREATE INDEX idx_schedule_doctor ON schedule_slot (doctor_id);
CREATE INDEX idx_schedule_weekday ON schedule_slot (weekday);

-- Конкретные записи пациентов
CREATE TABLE appointment (
    id          BIGSERIAL PRIMARY KEY,
    doctor_id   BIGINT      NOT NULL REFERENCES doctor (id),
    patient_id  BIGINT      NOT NULL REFERENCES app_user (id),
    slot_date   DATE        NOT NULL,
    start_time  TIME        NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'BOOKED'
                CHECK (status IN ('BOOKED', 'CANCELLED', 'COMPLETED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- На один слот — одна активная запись (место одно)
CREATE UNIQUE INDEX uq_appointment_active
    ON appointment (doctor_id, slot_date, start_time)
    WHERE status = 'BOOKED';

CREATE INDEX idx_appointment_patient ON appointment (patient_id, slot_date);
CREATE INDEX idx_appointment_date ON appointment (slot_date);

-- Журнал голосовых запросов (аудио + транскрипт + разобранный фрейм)
CREATE TABLE voice_request (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT REFERENCES app_user (id),
    audio_path   VARCHAR(255),
    content_type VARCHAR(64),
    transcript   TEXT,
    frame_json   TEXT,
    intent       VARCHAR(16),
    status       VARCHAR(16) NOT NULL,
    error        VARCHAR(512),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_voice_request_user ON voice_request (user_id, created_at DESC);
