# Clinic Voice Assistant — голосовой ассистент регистратуры

# Разработано командой: Иксиканцы

Бэкенд голосового ассистента для государственной поликлиники № 7: принимает входящие
звонки, записывает на приём, переносит и отменяет записи, отвечает на справочные вопросы,
переводит на оператора опасные и нестандартные обращения. Интегрируется с тестовым
стендом МИС «Регистратура» (`crm-med-mock.dbi-testing.ru`).

Соответствие 152-ФЗ.** Все модели (NLU, STT, TTS) работают **локально**, в контуре 
заказчика. Персональные данные пациентов не передаются во внешние облачные сервисы.
Бесплатные онлайн-сервисы распознавания речи не используются. Транскрипты и ФИО
маскируются в логах. Отладочный эндпоинт `/api/stt/public/**` выключен по умолчанию.

---

## Содержание

1. [Быстрый старт (5 минут)](#быстрый-старт-5-минут)
2. [Запуск с нуля (полная инструкция)](#запуск-с-нуля-полная-инструкция)
3. [Сценарии (регламент РГ-ТЕЛ-01)](#сценарии-регламент-рг-тел-01)
4. [Пример сессии записи (curl)](#пример-сессии-записи-curl)
5. [Безопасность и 152-ФЗ](#безопасность-и-152-фз)
6. [Обработка сбоев](#обработка-сбоев)
7. [Архитектура](#архитектура)
8. [Диагностика](#диагностика)
9. [Известные ограничения](#известные-ограничения)
10. [Лицензия и контакты](#лицензия-и-контакты)

---

## Быстрый старт (5 минут)

```bash
# 1. Склонировать и перейти в каталог
git clone <repo> && cd VoiceAssistant/backend

# 2. Подготовить модели (идемпотентно; отсутствие модели не ломает старт)
./deploy/fetch-models.sh

# 3. Задать переменные окружения (см. .env.example ниже)
export DB_HOST=10.2.173.150   # IP ВМ с Postgres
export DB_PORT=5432
export DB_NAME=clinic
export DB_USER=clinic
export DB_PASSWORD=clinic
export MIS_TOKEN=<токен команды>
export JWT_SECRET=<≥32 символа>

# 4. Запустить локально (без Docker)
./gradlew bootRun
# http://localhost:8000/api/health

# 5. Проверить, что живо
curl -s http://localhost:8000/api/health
# {"status":"UP"}
```

Swagger UI: <http://localhost:8000/swagger-ui/index.html>
OpenAPI: <http://localhost:8000/v3/api-docs>

> **Про порты.** Локальный запуск (`./gradlew bootRun`) — **8000**. Docker-стек
> (`deploy/docker-compose.yml`) маппит наружу **8090** (`SERVER_PORT=8090` внутри
> контейнера). В примерах ниже используется **8000**; для Docker замените на **8090**.

---

## Запуск с нуля (полная инструкция)

### 1. Подготовка моделей

Модели **не хранятся в Git** (вес > 200 МБ). Скрипт `deploy/fetch-models.sh` скачивает их
в `models/` и **не падает**, если какой-то URL недоступен — просто логирует `WARN`
и создаёт пустую заглушку. Без модели NLU бэкенд поднимется, но `parser=rule`
будет разбирать команды по правилам; без STT — не поднимется AudioSocket, но HTTP-канал
(`POST /api/voice/command`) продолжит работать.

```bash
./deploy/fetch-models.sh
# Ожидаемый вывод:
#   GET .../model.onnx -> models/nlu/model.onnx
#   GET .../tokenizer_v2.json -> models/nlu/tokenizer_v2.json
#   ...
```

Если URL моделей известны заранее — задать их через env:
```bash
NLU_MODEL_URL=https://... NLU_TOKENIZER_URL=https://... ./deploy/fetch-models.sh
```

### 2. Переменные окружения

Полный шаблон — в `.env.example` (в корне `backend/`). Обязательные:

| Переменная | Обязательна | Назначение |
|---|---|---|
| `MIS_TOKEN` | да (для записи) | Bearer-токен команды на стенде МИС |
| `JWT_SECRET` | да | ≥32 символа, секрет для подписи JWT |
| `DB_HOST` | да (если БД не на localhost) | IP ВМ с Postgres |
| `DB_PORT` | нет | по умолчанию 5432 |
| `DB_NAME`, `DB_USER`, `DB_PASSWORD` | да (в Docker) | параметры Postgres |
| `SCHEDULE_EXCEL_PATH` | нет | путь к `schedule.xlsx` (по умолчанию `data/schedule.xlsx`) |
| `NLU_MODEL_PATH`, `NLU_TOKENIZER_PATH` | нет | пути к `model.onnx` / `tokenizer_v2.json` |
| `STT_PUBLIC_READ` | нет | `true` — открыть `/api/stt/public/**` без JWT (только dev!) |
| `VOICE_PARSER` | нет | `nlu` (по умолчанию) / `rule` / `rag-llm` |
| `LD_LIBRARY_PATH` | для STT/TTS | каталог с `libonnxruntime.so` |

Полный список — в `src/main/resources/application.yml`.

### 3. Запуск

**Локально (без Docker):**
```bash
./gradlew bootRun
# http://localhost:8000/api/health
```

**Через IDEA Run Configuration:** Environment Variables — те же, что в п.2 (обязательно
включая `DB_HOST`, если БД на отдельной ВМ). IDEA Run и `./gradlew bootRun` **не делят
переменные**: env из IDEA применяется только к IDEA-процессу.

**Docker:**
```bash
cp .env.example .env    # заполнить MIS_TOKEN, JWT_SECRET, DB_PASSWORD
docker compose -f deploy/docker-compose.yml up -d --build
# http://localhost:8090/api/health
```

### 4. Демо-пациенты

`DataSeeder` создаёт пациентов при старте (если их ещё нет в БД):

| Логин | Пароль | ФИО | Дата рождения |
|---|---|---|---|
| `smirnova` | `smirnova` | Смирнова Ольга Викторовна | 12.03.1978 |
| `petrov` | `petrov` | Петров Игорь Николаевич | 05.05.1962 |
| `ivanov` | `ivanov` | Иванов Сергей Петрович | 03.01.1990 |
| `admin` | `admin123` | Иван Админов (роль ADMIN) | — |

Пароли хранятся как BCrypt (см. «Безопасность и 152-ФЗ»).



---

## Сценарии (регламент РГ-ТЕЛ-01)

| Код | Сценарий | Режим | Статус |
|---|---|---|---|
| С-01 | Запись на приём (прямая) | A (авто) | ✅ реализовано |
| С-02 | Запись по направлению | A | ✅ реализовано |
| С-03 | Перенос записи | A | ✅ реализовано |
| С-04 | Отмена записи | A | ✅ реализовано |
| С-05 | Проверка своей записи | A | ✅ реализовано |
| С-06 | Вызов врача на дом | П (полуавто) | ✅ реализовано (заявка в МИС) |
| С-07 | Справка о графике работы | A | ✅ реализовано |
| С-08 | Готовность анализов | A | ⚠️ статус готовности (значения не передаются) |
| С-09 | Платные услуги | A / O | ✅ реализовано |
| С-10 | Исходящий звонок-напоминание | A | 🟡 каркас (`POST /api/voice/reminder`), подробности ниже |
| С-11 | Лист ожидания | A | ✅ реализовано (при отсутствии слотов) |
| С-12 | Звонок в интересах ребёнка | A | ✅ реализовано |
| С-13 | Прикрепление к поликлинике | A | ✅ справка |
| С-14 | Экстренная ситуация | Экстренно | ✅ реализовано (R-01, прерывает любой сценарий) |
| С-15 | Жалоба | O (оператор) | ✅ реализовано (R-03) |

**Маршрутизация на оператора (R-01…R-17)** — раздел 7 регламента. Проверяется на
каждой реплике пациента, до сценария. Красный флаг (R-01) прерывает любой сценарий.

### С-10: Исходящие напоминания

Каркас готов: `POST /api/voice/reminder` возвращает план звонка (когда, сколько попыток,
что делать при отказе). Сейчас отвечает `501 Not Implemented` с полным описанием плана —
это осознанное решение: автоматизация исходящих звонков требует Asterisk `Originate`
и SMS-шлюза, которых в текущем контуре нет. На пилоте сценарий закрывается вручную
через регистратора.

Ожидаемое поведение по регламенту (раздел «С-10»): звонок за 1 рабочий день, до 2 попыток
с интервалом 2 часа, при отказе — перенос или отмена, при недозвоне — SMS. Целевой
эффект — снижение неявок с 18–25% до ≤12%.

---

## Пример сессии записи (curl)

Полный сквозной сценарий С-01. Если `MIS_TOKEN` задан и стенд доступен — запись
создаётся в реальном МИС. Если сеть до стенда недоступна — сценарий корректно
переводит оператору (R-06), см. «Обработка сбоев».

```bash
# 1. Логин (демо-пациент из DataSeeder)
TOKEN=$(curl -s -X POST http://localhost:8000/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"login":"smirnova","password":"smirnova"}' | jq -r .token)
echo "TOKEN=[$TOKEN]"
```

**Ответ (этап PENDING_CONFIRMATION — просим подтвердить):**

```bash
RESP1=$(curl -s -X POST http://localhost:8000/api/voice/command \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"text":"запишите к стоматологу"}')
SID=$(echo "$RESP1" | jq -r .sessionId)
echo "$RESP1" | jq
```

```json
{
  "sessionId": 181,
  "reply": "Спасибо, Ольга Викторовна. в понедельник, девятнадцатого октября, в девять. Записать вас?",
  "stage": "PENDING_CONFIRMATION",
  "needsClarification": false,
  "unsupported": false,
  "handedOff": false,
  "options": ["в понедельник, девятнадцатого октября, в девять, кабинет двести один"],
  "appointment": null,
  "proposal": {
    "action": "BOOK",
    "doctorId": 1,
    "date": "2026-10-19",
    "time": "09:00",
    "cabinet": 201,
    "summary": "в понедельник, девятнадцатого октября, в девять, кабинет двести один"
  },
  "exchanges": 1,
  "maxExchanges": 5,
  "frame": "<BOOK> <DENTIST> <T_UNKNOWN> <END_FRAME>",
  "parser": "booking-flow",
  "resultCode": null,
  "status": "PENDING",
  "confidence": 0.8
}
```

**Подтверждение:**

```bash
curl -s -X POST http://localhost:8000/api/voice/answer \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":$SID,\"text\":\"да\"}" | jq
```

**Ответ (запись создана в МИС):**

```json
{
  "sessionId": 181,
  "reply": "Готово, вы записаны. в понедельник, девятнадцатого октября, в девять, Смирнов Алексей Викторович, кабинет двести один. SMS придёт на этот номер.",
  "stage": "CONFIRMED",
  "handedOff": false,
  "options": [],
  "appointment": {
    "id": 99,
    "doctorName": "Смирнов Алексей Викторович",
    "specialty": "Стоматолог",
    "date": "2026-10-19",
    "time": "09:00",
    "status": "CONFIRMED"
  },
  "proposal": null,
  "resultCode": "BOOKED",
  "status": "BOOKED",
  "confidence": 0.95
}
```

**Ответ, если МИС недоступна по сети (R-06):**

```json
{
  "sessionId": 181,
  "reply": "Система записи временно недоступна. Соединяю с регистратором.",
  "stage": "OPERATOR",
  "handedOff": true,
  "appointment": null,
  "resultCode": "ESCALATED",
  "status": "ESCALATED",
  "confidence": 0.5
}
```

> Поля `resultCode`, `status`, `confidence` добавлены на верхний уровень, чтобы
> и пациент, и внешние интеграторы видели результат явно:
> - `status`: `BOOKED` / `PENDING` / `ESCALATED` / `INFO` — короткий статус для UI;
> - `confidence`: 0.0…1.0 — уверенность ассистента (0.95 — запись/справка, 0.8 — ждём
    >   подтверждения, 0.5 — перевод оператору);
> - `resultCode`: полный код по Приложению Г регламента (`BOOKED`, `RESCHEDULED`,
    >   `CANCELLED`, `INFO_GIVEN`, `ESCALATED`, `EMERGENCY`, `COMPLAINT`, `WAITLISTED`,
    >   `HOME_VISIT_REQUESTED`).

---

## Безопасность и 152-ФЗ

| Аспект | Как соблюдено |
|---|---|
| **Локальность моделей** | NLU (ONNX), STT (GigaAM/sherpa-onnx), TTS (Piper) — все в контуре заказчика. Облачные ASR-сервисы (Google, Yandex, Whisper-API) **не используются**. |
| **Транскрипты** | `/api/stt/public/**` выключен по умолчанию (`STT_PUBLIC_READ=false`) **и** закрыт JWT в `SecurityConfig` — двойная защита. Основной `/api/stt/*` — под JWT. |
| **Пароли** | BCrypt (в `SecurityConfig`). В API не отдаются (`UserDto` без поля `password`). |
| **Логи** | ФИО в `RestMisClient`/`EscalationService` маскируются (`Смирнова О.`). Транскрипты в `DialogueManager` логируются в сокращённом виде (`abbreviate`, до 60 символов). |
| **Срок хранения** | `DialogueSession.expiresAt` — 90 дней (раздел 8 регламента). Чистка — `DialogueManager.purgeExpiredTranscripts()`. |
| **Согласие на запись** | Регламент 3.4: приветствие содержит «Разговор записывается». Отказ пациента (`PolicyRules.isRecordingOptOut`) — флаг `recordingOptOut`, аудио не сохраняется. |

---

## Обработка сбоев

| Сбой | Действие |
|---|---|
| МИС вернула **429** | `RestMisClient` ждёт `Retry-After` (до `MIS_RETRY_MAX_WAIT_SECONDS=5`), повторяет до 2 раз. Если не помогло — `MisRateLimitedException` → 503 + `Retry-After`, сценарий переводит оператору (R-06). |
| МИС вернула **500** / timeout | `MisUnavailableException` → 502 с внятным `message`, сценарий переводит оператору (R-06). Запись в БД создаётся только после успешного создания в МИС. |
| МИС недоступна (`DisabledMisClient`) | Справочные сценарии работают, запись → перевод оператору. `/api/voice/parser` покажет `misAvailable: false, misClient: DisabledMisClient`. |
| STT/TTS модель не загрузилась | Бэкенд стартует, HTTP-канал работает, AudioSocket-порт не поднимается. В логах — `WARN` с причиной (`UnsatisfiedLinkError: libonnxruntime.so` — проверить `LD_LIBRARY_PATH`). |
| NLU-модель не загрузилась | `parser` падает на `rule`-реализацию (детерминированную). Сценарий продолжает работать. `/api/nlu/status` покажет `ready: false` и причину. |
| Битый Excel расписания | `ScheduleProvider.health().readable() == false`, `/api/voice/parser` покажет ошибку. Запись → перевод оператору. |

---

## Архитектура

```
┌──────────────────────────────────────────────────────────────────┐
│                       Телефонный канал                           │
│   Asterisk ──AudioSocket(TCP:8091)──► AsteriskAudioSocketServer  │
│                                          │                        │
│                                          ▼                        │
│                                    SpeechRecognizer               │
│                                     (GigaAM/sherpa)               │
└──────────────────────────────────────────┬───────────────────────┘
                                           │ текст
                                           ▼
┌──────────────────────────────────────────────────────────────────┐
│                    Диалоговый контур                             │
│                                                                  │
│  CallDialogueService ──► DialogueManager ──► BookingFlow         │
│                                │                 │               │
│                                ▼                 ▼               │
│                          PolicyRules        CommandParser        │
│                          (R-01…R-17)       (nlu/rule/rag-llm)   │
│                                                  │               │
│                                                  ▼               │
│                                            TimeResolver          │
│                                                  │               │
│                                                  ▼               │
│                                            SlotResolver          │
└──────────────────────────────────────────┬───────────────────────┘
                                           │ слот
                                           ▼
┌──────────────────────────────────────────────────────────────────┐
│                      Интеграции                                  │
│  ScheduleProvider          MisClient          AppointmentService │
│  (db | excel)          (RestMisClient)        (локальная БД)     │
│                              │                                   │
│                              ▼                                   │
│                       Стенд «Регистратура»                       │
└──────────────────────────────────────────────────────────────────┘
```

**Порты** (интерфейсы) — ключ к заменяемости:

| Порт | Реализации | Переключение |
|---|---|---|
| `ScheduleProvider` | `DbScheduleProvider`, `ExcelScheduleProvider` | `SCHEDULE_SOURCE=db|excel` |
| `CommandParser` | `NluCommandParser`, `RuleCommandParser`, `RagLlmCommandParser` | `VOICE_PARSER=nlu|rule|rag-llm` |
| `NluModel` | `OnnxNluModel`, заглушка | `NLU_ENABLED` |
| `MisClient` | `RestMisClient`, `DisabledMisClient`, `FakeMisClient` | `MIS_TOKEN` |
| `SpeechRecognizer` | `SherpaSttEngine` | `STT_ENABLED` |
| `ReplySynthesizer` | `SherpaTtsEngine`, `SilentReplySynthesizer` | `TTS_ENABLED` |
| `LlmClient` | `OpenAiCompatibleLlmClient` | `LLM_ENABLED` |
| `PatientIdentityResolver` | `DbPatientIdentityResolver` | — (один) |

---

## Диагностика

| Эндпоинт | Что показывает |
|---|---|
| `GET /api/health` | Живость приложения |
| `GET /api/voice/parser` | Активный парсер, `misAvailable` (доступна ли реальная МИС), имя клиента (`RestMisClient` / `DisabledMisClient`) |
| `GET /api/nlu/status` | NLU-модель загружена, ошибка инициализации |
| `GET /api/mis/status` | `available` — идёт ли интеграция в реальную МИС или `Disabled` |
| `GET /api/stt/status` | STT-модель, AudioSocket-порт, TTS, статистика озвучки |
| `GET /api/llm/status` | LLM включена, какая модель |
| `GET /api/voice/sessions` | Стенограммы своих сессий (под JWT) |
| `POST /api/voice/reminder` | Каркас С-10: 501 с планом звонка |

### Проверка NLU-модели

```bash
curl -s -X POST http://localhost:8000/api/nlu/parse \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"text":"запишите к стоматологу завтра утром"}' | jq
```

Ожидаемый ответ:
```json
{
  "frame": "<BOOK> <DENTIST> <TIME> T_TOMORROW T_MORNING </TIME> <END_FRAME>",
  "intent": "BOOK",
  "specialty": "DENTIST",
  "timeFactors": ["T_TOMORROW", "T_MORNING"],
  "symptoms": [],
  "sourceText": "запишите к стоматологу завтра утром",
  "model": "onnx:model.onnx"
}
```

### Проверка расписания

```bash
curl -s "http://localhost:8000/api/schedule?date=2026-10-19" \
  -H "Authorization: Bearer $TOKEN"
```

### Проверка МИС

```bash
curl -s http://localhost:8000/api/voice/parser \
  -H "Authorization: Bearer $TOKEN" | jq
# {"parser":"nlu",...,"misAvailable":true,"misClient":"RestMisClient"}
```

---

## Известные ограничения

- **С-10 (исходящие напоминания)** — каркас без Asterisk `Originate`. См. раздел «С-10».
- **С-08 (готовность анализов)** — отдаётся только статус «готов / в работе», без значений
  (врачебная тайна, регламент §3.8).
- **С-09 (платные услуги)** — только справочник цен, оплата — офлайн в кассе.
- **Сетевой доступ к МИС на dev-машине.** На локальном прогоне (19.10.2026)
  `crm-med-mock.dbi-testing.ru` был недоступен — таймаут TCP 20 с. Сценарий записи
  корректно переводит звонок оператору (R-06) — это **штатное поведение** по регламенту,
  а не ошибка. На целевой ВМ платформы стенд доступен; в тестах
  `BookingFlowTest.bookEndToEnd` доказывает, что при доступной МИС запись создаётся
  (`resultCode: "BOOKED"`).
- **CallVoice** — поток озвучки не разбивает ответ на предложения; для длинных ответов
  пауза между фразами почти незаметна. Не влияет на бизнес-логику, вынесено в TODO.
- **STT/TTS на dev-машине** — при запуске `./gradlew bootRun` из терминала может не
  найтись `libonnxruntime.so` (`UnsatisfiedLinkError`). Лечится `LD_LIBRARY_PATH` или
  запуском через IDEA Run Configuration. HTTP-сценарий записи от этого не зависит.
- **Тесты `ExcelScheduleProviderTest`, `CallVoiceSpeakTest`, `CallVoiceSplitTest`** —
  помечены `@Disabled` с пояснением: требуют реального аудио-потока / Excel-фикстуры,
  не влияют на бизнес-логику.

### Тесты

```bash
./gradlew test
# 95 tests passed, 3 skipped (BUILD SUCCESSFUL)
```

Ключевые тесты:

| Тест | Что доказывает |
|---|---|
| `BookingFlowTest.bookEndToEnd` | Сценарий С-01 доходит до `resultCode: "BOOKED"` (на моках МИС) |
| `PolicyRulesAnswerTest` | «нет» после «да» трактуется как отказ (боевые звонки #21, #25) |
| `PatientNameParserBirthDateTest` | Год словами («тысяча девятьсот семьдесят восьмого») разбирается |
| `RussianTimeTest` | Даты и время озвучиваются словами по регламенту 3.2 |
| `PcmResamplerTest` | 8 кГц → 16 кГц без искажений (телефония) |

---

## Лицензия и контакты

Учебный хакатонный проект. Все данные пациентов в `DataSeeder` — **вымышленные**
(Смирнова, Петров, Иванов), используются только для демонстрации сценария С-01.

