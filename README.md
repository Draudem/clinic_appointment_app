# Clinic Voice Assistant — Backend

Spring Boot 3.5 / Java 21 / PostgreSQL бэкенд хакатонного голосового ассистента
клиники: JWT-авторизация, расписание врачей, записи на приём и конвейер
обработки голосовых обращений (аудио → текст → фрейм → поиск слота).

## Стек

| Компонент | Технология |
|---|---|
| API | Spring Boot 3.5.4, Java 21, Spring Security (JWT, jjwt 0.12.6), BCrypt |
| БД | PostgreSQL, Flyway (`V1__init.sql`), `ddl-auto: validate` |
| ИИ | Два сменяемых этапа через интерфейс-порт + `@ConditionalOnProperty` |
| Доки | springdoc-openapi → `/swagger-ui.html` |

## Быстрый старт

```bash
# 1. PostgreSQL (например, контейнер)
docker run -d --name clinic-pg -e POSTGRES_USER=clinic \
  -e POSTGRES_PASSWORD=clinic -e POSTGRES_DB=clinic -p 5432:5432 postgres:16-alpine

# 2. Сборка
./gradlew bootJar          # или: gradle bootJar

# 3. Запуск (демо-данные наливаются автоматически, SEED_ENABLED=true)
java -jar build/libs/clinic-voice-backend-0.1.0.jar
```

Swagger: <http://localhost:8080/swagger-ui.html>

### Переменные окружения

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | localhost/5432/clinic/clinic/clinic | Подключение к PostgreSQL |
| `JWT_SECRET` | dev-секрет | Секрет HS256 (в проде ≥ 32 байт) |
| `JWT_TTL` | 24h | Время жизни токена |
| `SERVER_PORT` | 8080 | Порт API |
| `STT_PROVIDER` | `http` | Этап распознавания речи: `stub` \| `http` |
| `STT_URL` | `http://localhost:8001/transcribe` | URL STT-сервиса |
| `STT_STUB_TEXT` | «записаться к терапевту завтра в десять утра» | Текст заглушки STT |
| `PARSER_PROVIDER` | `http` | Этап разбора текста: `stub` \| `http` |
| `PARSER_URL` | `http://localhost:8002/parse` | URL парсер-сервиса |
| `SEED_ENABLED` | true | Наполнить пустую БД демо-данными |
| `ALLOW_ADMIN_REGISTRATION` | false | Публичная регистрация админов |
| `CORS_ORIGINS` | `*` | Разрешённые origins |

## Архитектура: сменяемые этапы ИИ

Каждый этап — это **порт** (интерфейс) в Java и **реализация**, выбираемая
одной строкой конфигурации (`src/main/java/com/clinic/assistant/config/AiClientsConfig.java`):

```
аудио ──► SpeechToTextClient ──► текст ──► IntentParserClient ──► фрейм ──► слоты ──► ответ
             │ stub | http                   │ stub | http
             ▼                               ▼
      POST /transcribe                POST /parse
      (ai-services/stt-service)       (ai-services/parser-service, ONNX-модель models/model.onnx)
```

* `stub` — встроенная заглушка (детерминированный текст / фиксированный фрейм), работает без внешних сервисов;
* `http` — обращение к отдельному сервису по контракту из [`docs/ai-contract.md`](docs/ai-contract.md).

Менять провайдера можно и в `application.yml`, и через переменные окружения —
остальной код конвейера не трогается. Если AI-сервис недоступен, бэкенд
отвечает `502 {"error":"AI_UNAVAILABLE"}` и логирует причину; этапы, не
затрагивающие ИИ (расписание, записи, пользователи), продолжают работать.

Подробности контрактов и примеры запросов: [`docs/ai-contract.md`](docs/ai-contract.md).
Интеграция мобильного приложения: [`docs/mobile-integration.md`](docs/mobile-integration.md).
Подмена `js/api.js` фронтенда: [`docs/frontend-api.js`](docs/frontend-api.js).

## Эндпоинты

### Авторизация
| Метод | Путь | Доступ | Описание |
|---|---|---|---|
| POST | `/api/auth/register` | публично | Регистрация (login, password, fio, role: PATIENT/ADMIN) |
| POST | `/api/auth/login` | публично | Вход → `{token, login, fio, role, roleCode}` |
| GET | `/api/auth/me` | с токеном | Текущий пользователь |

### Расписание и врача (фронтенд)
| Метод | Путь | Доступ | Описание |
|---|---|---|---|
| GET | `/api/doctors` | с токеном | Все врача |
| GET | `/api/doctors/{id}` | с токеном | Врач |
| GET | `/api/doctors/{id}/slots` | с токеном | Конкретные слоты `{id:"12@2026-10-09T10:30", date, time, isAvailable}` |
| GET | `/api/schedule?date=YYYY-MM-DD` | с токеном | Приёмы на дату |
| GET | `/api/calendar?year=&month=` | с токеном | Бейджи календаря `[{date, count}]` |
| GET | `/api/schedule/unique-times` | с токеном | Уникальность времени у врачей `{"ok":true}` |
| GET | `/api/employees` | с токеном | Таблица сотрудников: ФИО, специальность, ближайший приём |
| GET | `/api/specialties` | с токеном | Специальности и их заболевания |
| GET | `/api/users` | ADMIN | Пользователи (пароль маскируется `••••••`) |

### Записи
| Метод | Путь | Доступ | Описание |
|---|---|---|---|
| POST | `/api/appointments` | с токеном | `{doctorId, date, time}` → 201; 400, если врача нет в это время |
| GET | `/api/appointments` | с токеном | Пациент — свои, админ — все (`?date=&doctorId=`) |
| DELETE | `/api/appointments/{id}` | с токеном | Отмена → 204 |

### Голос
| Метод | Путь | Доступ | Описание |
|---|---|---|---|
| POST | `/api/voice/command` | с токеном | `multipart: audio` → полный конвейер → фрейм + варианты записи |
| POST | `/api/voice/transcribe` | с токеном | Только этап STT |
| POST | `/api/voice/parse` | с токеном | `{"text"}` → разбор без аудио (отладка/веб) |
| GET | `/api/voice/requests` | ADMIN | Журнал голосовых запросов |

### Мобильное
| Метод | Путь | Доступ | Описание |
|---|---|---|---|
| GET | `/api/mobile/doctors?fromDate=&days=14` | с токеном | Врачи со слотами в формате `com.clinic.clinicapp.data.model` |

## Тесты

```bash
./gradlew test     # 17 unit-тестов: VoiceFrame, стаб парсера, TimeWindowResolver
```

## Структура

```
src/main/java/com/clinic/assistant/
├── auth/        # JWT, регистрация/вход, роли
├── clinic/      # врача, расписание, специальности, записи, пользователи
├── config/      # SecurityConfig, AiClientsConfig (переключение этапов ИИ)
├── voice/       # VoicePipelineService — оркестрация STT → парсер → слоты
├── ai/          # порты SpeechToTextClient / IntentParserClient + stub/http
└── common/      # ошибки, DTO
src/main/resources/
├── application.yml
└── db/migration/V1__init.sql   # схема + демо-данные (12 врачей, 4 пользователя)
```

## Docker

```bash
docker compose up --build     # db:5432 + backend:8080 + parser:8002 + stt:8001
```

Состав: PostgreSQL, бэкенд, STT-заглушка и парсер-сервис с ONNX-моделью
(монтируется каталог `../models`). См. `docker-compose.yml` в корне `VoiceAssistant/`.
