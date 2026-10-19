# Clinic Voice Backend

Голосовой ассистент регистратуры поликлиники: приём звонков по Asterisk AudioSocket,
локальное распознавание речи (GigaAM), локальный синтез (Piper), NLU-модель
(ONNX, constrained decoding), интеграция с тестовым стендом МИС «Инфоклиника».

**Стек:** Java 21, Spring Boot 3.5, PostgreSQL 16, ONNX Runtime, sherpa-onnx.
**Демо:** `http://<host>:8000/` → редирект на Swagger UI.
**Телефонный канал:** TCP `:8091` (AudioSocket), подключается Asterisk.

---

## 1. Что работает (по регламенту РГ-ТЕЛ-01)

| Сценарий | Код результата | Статус | Где в коде |
|---|---|---|---|
| C-01 Запись на приём | `BOOKED` | **реализовано** | `BookingFlow.doBook` → `RestMisClient.createVisit` |
| C-03 Перенос | `RESCHEDULED` | **реализовано** | `BookingFlow.doReschedule` (создать → отменить, атомарно) |
| C-04 Отмена | `CANCELLED` | **реализовано** | `BookingFlow` + `AppointmentService.cancel` |
| C-05 Проверка записи | `INFO_GIVEN` | **реализовано** | `BookingFlow.activeBooking` |
| C-07 График работы | `INFO_GIVEN` | **реализовано** | `ScheduleQueryService` (С-07) |
| C-14 Экстренная ситуация | `EMERGENCY` | **реализовано** | `PolicyRules` R-01, категории А/Б |
| C-15 Жалоба | `COMPLAINT` | **реализовано** | `PolicyRules` R-03 |
| C-06 Вызов на дом | `HOME_VISIT_REQUESTED` | каркас | `/api/voice` — сбор данных; МИС-заявка не отправляется |
| C-10 Напоминания | `CONFIRMED/NO_ANSWER/OPT_OUT` | **план + фиксация** | `ReminderService` — см. §6 |
| C-11 Лист ожидания | `WAITLISTED` | каркас | формулировка есть, очереди нет |

**Сквозной сценарий C-01 подтверждён живым звонком** (лог: [`docs/call-log-sample.txt`](docs/call-log-sample.txt)):
приветствие → идентификация → предложение слота → подтверждение → `BOOKED` в МИС.

---

## 2. Быстрый старт

Мы поддерживаем два способа запуска: напрямую на хосте (используется на нашем VPS) и через Docker.

### 2.1. Запуск на хосте (Production VPS)
```bash
# 1. зависимости и модели (~200 МБ)
./deploy/fetch-models.sh

# 2. конфигурация (секреты НЕ в репозитории)
cp .env.example .env.local
$EDITOR .env.local           # MIS_TOKEN, JWT_SECRET (>=32), DB_PASSWORD
chmod 600 .env.local

# 3. БД (локальный postgres:16)
docker compose -f deploy/docker-compose.local.yml up -d

# 4. сборка и запуск
./gradlew bootJar
./start_server.sh            # читает .env.local, слушает :8000
```

### 2.2. Запуск через Docker (Альтернатива)
Если вы хотите запустить всё в контейнерах, используйте `deploy/docker-compose.yml`.
Убедитесь, что переменные окружения проброшены (файл `.env` рядом с compose).
```bash
docker compose -f deploy/docker-compose.yml up -d --build
```

**Секреты:** в репозитории только `.env.example` с плейсхолдерами. `.env.local`
в `.gitignore`. `start_server.sh` без `.env.local` падает с понятной ошибкой —
случайно задеплоить с пустым токеном нельзя.

---

## 3. Пример реального HTTP-обмена (запись)

### 3.1. Логин
```bash
curl -s -X POST localhost:8000/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"login":"smirnova","password":"smirnova"}'
```
```json
{"token":"eyJhbGciOiJIUzI1NiJ9...","login":"smirnova","role":"PATIENT",
 "firstName":"Ольга","lastName":"Смирнова"}
```

### 3.2. Запись (шаг 1 — намерение)
```bash
curl -s -X POST localhost:8000/api/voice/command \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"text":"хочу записаться к стоматологу"}'
```
```json
{"sessionId":1,"reply":"Спасибо, Ольга Викторовна. Записываю: в четверг, первого октября, в девять тридцать, кабинет двести один. Верно?",
 "stage":"PENDING_CONFIRMATION","needsClarification":false,"unsupported":false,
 "handedOff":false,"options":["в четверг, первого октября, в девять тридцать"],
 "appointment":null,
 "proposal":{"action":"BOOK","doctorId":3,"date":"2026-10-01","time":"09:30",
             "cabinet":201,"appointmentId":null,"summary":"в четверг, первого октября, в девять тридцать"},
 "exchanges":1,"maxExchanges":5,"frame":"<BOOK> <DENTIST> <TIME> T_TOMORROW T_MORNING </TIME> <END_FRAME>",
 "parser":"booking-flow","resultCode":null,"status":"PENDING","confidence":0.8}
```

### 3.3. Подтверждение (шаг 2 — запись в МИС)
```bash
curl -s -X POST localhost:8000/api/voice/answer \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"sessionId":1,"text":"да"}'
```
```json
{"sessionId":1,"reply":"Готово, вы записаны. в четверг, первого октября, в девять тридцать, кабинет двести один. SMS придёт на этот номер.",
 "stage":"CONFIRMED","needsClarification":false,"unsupported":false,"handedOff":false,
 "options":[],"appointment":{"id":99,"doctorId":3,"doctorName":"Сидорова Клавдия Егоровна",
   "specialty":"Стоматолог-терапевт","date":"2026-10-01","time":"09:30","status":"CONFIRMED"},
 "proposal":null,"exchanges":2,"maxExchanges":5,"frame":"...","parser":"booking-flow",
 "resultCode":"BOOKED","status":"BOOKED","confidence":0.95}
```

**Ключевые поля для интегратора:**
- `status` — `BOOKED | PENDING | ESCALATED | INFO | DONE` (для UI);
- `resultCode` — код из Приложения Г (`BOOKED`, `RESCHEDULED`, `EMERGENCY`, ...);
- `confidence` — 0.95 при подтверждённой записи, 0.8 при ожидании подтверждения,
  0.5 при переводе оператору (ассистент честно признаёт, что не справился);
- `handedOff: true` + `resultCode: "ESCALATED"` — перевод, причина в `stage`/логе.

---

## 4. Телефонный канал (Asterisk AudioSocket)

Приём звонка: TCP `:8091`, протокол AudioSocket (TLV, PCM 16-bit 8 kHz little-endian).
Asterisk подключается сюда **не по HTTP** — это отдельный порт.

### 4.1. Ключевые решения (иначе звонок не работает)

| Место | Решение | Зачем |
|---|---|---|
| `extensions.conf` | `AudioSocket(...,c(slin))` | µ-law → PCM16; без `c(slin)` GigaAM получает мусор |
| `CallVoice.ECHO_TAIL_MS=800` | входящее аудио игнорируется 800 мс после TTS | гасит акустическое эхо из динамика телефона |
| `segmenter.reset()` после TTS | очистка накопителя фраз | эхо не «прилипает» к фразе пациента |
| `CallVoice.awaitFinished()` | ждёт `queue.isEmpty() && !speaking` | иначе соединение закрывается до конца фразы |
| `PcmResampler` | 8→16 кГц, state между чанками | Asterisk шлёт 320 байт (~319 сэмплов 16 кГц) |

Полный лог эталонного звонка — [`docs/call-log-sample.txt`](docs/call-log-sample.txt).

### 4.2. Диагностика
`/api/stt/status` — модель, порт, `listening`, счётчики `spokenOk/spokenFailed`.
`/api/stt/public/last` — последняя фраза (только при `STT_PUBLIC_READ=true`, по умолчанию **выключено**).
`AUDIO_RX`/`VAD`/`STT_RAW` — `log.debug`, включаются `app.stt.debug-audio=true`.

---

## 5. Ограничения кейса и как они соблюдены

| Ограничение | Как соблюдено |
|---|---|
| 152-ФЗ: ПДн не уходят в облака | STT (GigaAM) и TTS (Piper) — **локальные** ONNX-модели; LLM по умолчанию `LLM_ENABLED=false` |
| 152-ФЗ: маскировка ПДн в логах | `RestMisClient.mask()` → «Смирнова О.»; логи не содержат ФИО целиком |
| 152-ФЗ: срок хранения стенограмм | `DialogueSession.expiresAt` = +90 дней, `purgeExpiredTranscripts()` |
| 152-ФЗ: отказ от записи | `PolicyRules.isRecordingOptOut` → `recordingOptOut=true` |
| Медицинские вопросы → оператор | `PolicyRules` R-04, граничные фразы (раздел 3.8) |
| Красные флаги → 103/112 | `PolicyRules` R-01 (категория А), R-11 (Б); `BookingFlow` прерывает сценарий |
| Просьба человека → немедленно | `PolicyRules` R-02, без уговоров |
| Лимит МИС 10/мин | `RestMisClient.rateLimit()` 8/мин + retry на 429 (`Retry-After`, до 2 попыток) |
| Нельзя менять отменённую запись | `RestMisClient.cancelVisit` → PATCH `status=Отменена` |

---

## 6. С-10: Исходящие напоминания

**Статус:** планировщик и фиксация результата реализованы; `Originate` через Asterisk AMI —
этап 2 пилота (не в этой итерации).

```bash
# план обзвона на завтра
curl -s -X POST localhost:8000/api/voice/reminder/plan \
  -H "Authorization: Bearer $TOKEN"
# {"date":"2026-10-20","tasks":[
#   {"appointmentId":42,"clientLogin":"smirnova","slot":"2026-10-20 09:30",
#    "doctorName":"Сидорова Клавдия Егоровна","attempts":0}]}

# фиксация результата обзвона
curl -s -X POST localhost:8000/api/voice/reminder/mark \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"appointmentId":42,"resultCode":"CONFIRMED"}'
# {"appointmentId":42,"resultCode":"CONFIRMED","recorded":true}
```

Регламент (С-10): за 1 рабочий день, до 2 попыток с интервалом 2 часа, при недозвоне — SMS.
Коды результата (`CONFIRMED`, `RESCHEDULED`, `CANCELLED`, `NO_ANSWER`, `OPT_OUT`) — в коде и API.

---

## 7. Тесты

```
$ ./gradlew test
...
BUILD SUCCESSFUL
95 tests completed, 3 skipped
```

Полный лог: [`docs/test-report.txt`](docs/test-report.txt).

**Что покрыто тестами (ключевое):**
- `BookingFlowTest` — полный цикл С-01 (намерение → идентификация → подтверждение → BOOKED);
- `IdentityRetryTest` — лестница Р-05 (2 неудачи → оператор);
- `PolicyRulesAnswerTest` — «последнее слово» («верно нет» = отказ), боевые звонки #21/#25;
- `RussianTimeTest` — разбор/озвучка дат («в четверг, первого октября»);
- `PatientNameParserBirthDateTest` — год словами («тысяча девятьсот семьдесят восьмого»);
- `ScheduleQueryServiceTest` — С-07 не уходит в запись;
- `SlotHorizonTest` — горизонт 14 дней, детерминированные часы;
- `PcmConverterTest`, `PcmResamplerTest` — телефония (little-endian, 8→16 кГц, VAD).

3 `@Disabled` — тесты, требующие реального аудио-потока Asterisk (в CI не воспроизводимы);
их роль выполняет `docs/call-log-sample.txt`.

---

## 8. Архитектура

```
voice/    BookingFlow (сценарий), PolicyRules (R-01..R-17), SlotResolver, TimeResolver,
          ScheduleQueryService (С-07), RussianTime, DialogueManager
nlu/      OnnxNluModel + FrameAutomaton (constrained decoding), BpeTokenizer
stt/      AsteriskAudioSocketServer (AudioSocket), CallVoice (TTS-поток),
          SherpaSttEngine (GigaAM), SherpaTtsEngine (Piper), PcmResampler
mis/      MisClient (порт) → RestMisClient (стенд) | DisabledMisClient | FakeMisClient
schedule/ ScheduleProvider (порт) → DbScheduleProvider | ExcelScheduleProvider
identity/ PatientIdentityResolver, PatientNameParser (детерминированный, без нейросети)
security/ JwtService, JwtAuthFilter, BCryptPasswordEncoder
```

Замена любого этапа (NLU/STT/TTS/МИС/расписание) — один бин в конфиге, остальной код не меняется.

---

## 9. Секреты и эксплуатация

- **В репозитории секретов нет.** Только `.env.example` с плейсхолдерами.
- `.env.local` — в `.gitignore`, читается `start_server.sh`.
- `JWT_SECRET` — минимум 32 символа (проверка в `JwtService` и `start_server.sh`).
- Пароли — BCrypt(10), `PlainTextPasswordEncoder` удалён.
- `STT_PUBLIC_READ=false` по умолчанию — транскрипты под JWT.
- Диагностические `STT_RAW`/`AUDIO_RX`/`VAD` — `log.debug`, включаются `app.stt.debug-audio=true`.
