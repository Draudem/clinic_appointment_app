# Интеграция мобильного приложения (ClinicApp)

Документ описывает, как заменить in-memory `AppointmentRepository` в
`mobile/ClinicApp` на реальные вызовы бэкенда и как провести голосовой
запрос через серверный конвейер.

Базовый адрес API: `http://<host>:8080/api` (для эмулятора Android —
`http://10.0.2.2:8080/api`). Все эндпоинты, кроме `POST /auth/login` и
`POST /auth/register`, требуют заголовок:

```
Authorization: Bearer <JWT>
```

## 1. Авторизация

```http
POST /api/auth/login
Content-Type: application/json

{"login": "patient", "password": "patient123"}
```

```json
{"token": "eyJhbGciOi...", "login": "patient", "fio": "Иванов Иван",
 "role": "пациент", "roleCode": "PATIENT"}
```

Токен хранится в SharedPreferences/DataStore и подставляется в каждый
запрос (OkHttp-интерцептор). Роли в БД: `PATIENT` (пациент),
`ADMIN` (администратор). Демо-пользователи: `admin/admin123`,
`patient/patient123`, `manager/manager456`, `doctor1/doctor123`.

## 2. Список врачей и слотов

```http
GET /api/mobile/doctors?fromDate=2026-10-08&days=14
```

Ответ повторяет `com.clinic.clinicapp.data.model` — поля совпадают 1:1:

```json
[
  {
    "id": "1",
    "name": "Иванов Пётр Сергеевич",
    "specialty": "Кардиолог",
    "availableSlots": [
      {"id": "3@2026-10-09T09:00", "date": "2026-10-09",
       "time": "09:00", "isAvailable": true}
    ]
  }
]
```

`Doctor` / `TimeSlot` из `Models.kt` десериализуются без изменений (Gson/Moshi
с `lenient` не нужен — все поля присутствуют).

## 3. Запись на приём

```http
POST /api/appointments
Content-Type: application/json

{"doctorId": 4, "date": "2026-10-09", "time": "10:30"}
```

* `201` → тело `AppointmentDto {id, doctorId, fio, specialty, patient, date, time, status, createdAt}`;
* `400 {"error":"BAD_REQUEST","message":"У врача «…» нет приёма в это время…"}` — слот не существует/занят;
* `401` — нет токена.

Список: `GET /api/appointments` (пациент видит свои, администратор — все).
Отмена: `DELETE /api/appointments/{id}` → `204`.

> В мобильном `Appointment.id/doctorId` — строки; на сервере это `Long`.
> Используйте строковое представление числа: Gson по умолчаниюNumbers→String
> конвертирует, либо сделайте `id`/`doctorId` типа `Long` в DTO.

## 4. Голосовой запрос

Два сценария (оба уже поддерживаются бэкендом):

### A. Аудио на сервер (рекомендуется: единый STT)

Локальный `SherpaSttEngine` не обязателен — можно отправить запись как есть:

```http
POST /api/voice/command
Authorization: Bearer <JWT>
Content-Type: multipart/form-data; boundary=...

--boundary
Content-Disposition: form-data; name="audio"; filename="cmd.wav"
Content-Type: audio/wav

<байты записи>
--boundary--
```

Ответ конвейера (аудио → STT → парсер → поиск слота):

```json
{
  "requestId": 7,
  "text": "записаться к терапевту завтра в десять утра",
  "frame": {
    "intent": "BOOK",
    "specialty": "GP",
    "timeFactors": ["T_TOMORROW", "T_H10_00"],
    "symptoms": [],
    "needsClarification": false,
    "raw": "<BOOK> <GP> <TIME> T_TOMORROW T_H10_00 </TIME> <END_FRAME>"
  },
  "resolved": {
    "specialty": "Терапевт",
    "dateFrom": "2026-10-09", "dateTo": "2026-10-09",
    "timeFrom": "10:00", "timeTo": "10:00"
  },
  "candidates": [
    {"doctorId": 4, "fio": "Попова Елена Игоревна", "specialty": "Терапевт",
     "date": "2026-10-09", "time": "10:30", "slotId": 12, "available": true}
  ],
  "message": "Найдено вариантов: 1"
}
```

Поля могут отсутствовать (`jackson: non_null`):

* `question` — сервер сам задаёт уточняющий вопрос (нет слотов, не понял запрос);
* `candidates` — свободные окна; `slotId` = id слота для подтверждения записи;
* `frame` — «сырой» результат парсера, удобно для отладки и показа в UI.

Сценарий UI: показать `message` → список `candidates` → по кнопке
«Записать» вызвать `POST /api/appointments` с `doctorId/date/time`
из выбранного кандидата.

### B. Текст от локального STT (если распознавание на устройстве)

Если приложение распознаёт речь само (`SherpaSttEngine`), отправляйте текст:

```http
POST /api/voice/parse
Content-Type: application/json

{"text": "хочу к неврологу на среду"}
```

Ответ identical, поле `text` — переданная строка.

Ошибки конвейера:

| Код | Ответ | Что делать в UI |
|---|---|---|
| 400 | `VALIDATION_FAILED` | пустой текст/аудио |
| 401/403 | `UNAUTHORIZED` | перелогиниться |
| 502 | `AI_UNAVAILABLE` | «Сервис ИИ временно недоступен, попробуйте позже» |

Журнал запросов (для экрана администратора): `GET /api/voice/requests`.

## 5. Минимальная замена репозитория

```kotlin
class ApiAppointmentRepository(
    private val api: ClinicApi,          // Retrofit
    private val auth: TokenStorage
) : AppointmentRepository {

    override val doctors = MutableStateFlow<List<Doctor>>(emptyList())
    override val appointments = MutableStateFlow<List<Appointment>>(emptyList())

    suspend fun refreshDoctors(fromDate: String, days: Int = 14) {
        doctors.value = api.mobileDoctors(fromDate, days)  // GET /mobile/doctors
    }

    override fun book(doctorId: String, slotId: String): Boolean {
        val (d, t, time) = slotId.split("@")                 // "12@2026-10-09T10:30"
        runBlocking {
            api.createAppointment(CreateRequest(d.toLong(), t.substring(0, 10), time))
            refreshAppointments()
        }
        return true
    }

    override fun findDoctorByName(query: String): Doctor? =
        doctors.value.find { it.name.contains(query, ignoreCase = true) }
}
```

Голосовой сценарий (ViewModel):

```kotlin
val audio: File = recorder.stop()                       // wav
val resp = api.voiceCommand(audio.asMultipart("audio")) // POST /voice/command
uiState.value = if (resp.candidates.isNullOrEmpty())
    UiState.Ask(resp.question ?: resp.message)
else UiState.Choose(resp.candidates)
```

## 6. Типы ошибок бэкенда

```json
{"error": "BAD_REQUEST", "message": "человекочитаемое сообщение", "fields": {"login": "..."}}
```

`error`: `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `BAD_REQUEST`,
`VALIDATION_FAILED`, `CONFLICT`, `AI_UNAVAILABLE`, `INTERNAL`.
