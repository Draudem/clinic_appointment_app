# Контракты ИИ-этапов (AI Contract)

Конвейер голосовой команды разбит на независимые этапы. Бэкенд обращается к
каждому этапу через **порт** (`ai/SpeechToTextClient`, `ai/IntentParserClient`)
и знает только этот HTTP-контракт. Заменить этап на свою модель = написать
сервис с тем же контрактом и указать URL в `application.yml` /
переменной окружения. Код бэкенда при этом не меняется.

```
┌────────┐  multipart   ┌──────────────┐  JSON   ┌───────────────┐  JSON   ┌─────────┐
│ mobile │ ───────────► │ backend      │ ──────► │ STT-сервис    │ ──────► │ текст   │
│ /web   │              │ /voice/*     │         │ :8001         │         └────┬────┘
└────────┘              └──────┬───────┘         │ (коллега или  │              │
                               │                 │  заглушка)    │              ▼
                               │   JSON          └───────────────┘      ┌───────────────┐
                               │  ◄───────────────────────────────────  │ Parser-сервис │
                               │  фрейм (intent/specialty/time/...)     │ :8002         │
                               ▼                                        │ (model.onnx)  │
                        поиск слотов, ответ пользователю                └───────────────┘
```

## Этап 1. Speech-to-Text — `POST /transcribe`

**Запрос** (multipart/form-data):

| Поле | Тип | Описание |
|---|---|---|
| `file` | файл | аудио (wav/ogg/webm, до 15 МБ) |
| `language` | строка | код языка, например `ru` |

**Ответ** `200 application/json`:

```json
{ "text": "запиши меня к терапевту завтра в 10:30", "model": "whisper-small-ru" }
```

| Поле | Тип | Обяз. | Описание |
|---|---|---|---|
| `text` | string | да | распознанный текст (пустая строка — не разобрано) |
| `model` | string | нет | имя/версия модели (для журнала) |

Ошибки: `4xx/5xx` с телом `{"error": "..."}` — бэкенд ответит клиенту
`502 AI_UNAVAILABLE` и залогирует причину. Таймаут чтения — 60 с.

Реализации:

* **Заглушка** — `ai-services/stt-service/server.py` (stdlib, без зависимостей):
  возвращает фиксированную фразу (`STT_STUB_TEXT`). Нужна, чтобы собрать и
  отладить конвейер без модели распознавания.
* **Настоящая модель** — сервис коллеги с тем же контрактом; запуск и URL
  меняются только в конфиге:

```yaml
app:
  ai:
    stt:
      provider: http                      # stub | http
      url: ${STT_URL:http://localhost:8001/transcribe}
```

## Этап 2. Разбор текста во фрейм — `POST /parse`

Модель из `models/model.onnx` (3.3M, ONNX, int64 вход `[1,128]`), инференс
через `onnxruntime` + токенизатор `models/tokenizer.json` (HF `tokenizers`,
ByteLevel BPE). Реализация: `ai-services/parser-service/server.py`.

**Запрос**:

```json
{ "text": "хочу к неврологу на среду" }
```

**Ответ**:

```json
{
  "intent": "BOOK",
  "specialty": "NEUROLOGIST",
  "time": ["T_WED"],
  "symptoms": [],
  "needsClarification": false,
  "raw": "<BOOK> <NEUROLOGIST> <TIME> T_WED </TIME> <END_FRAME>"
}
```

| Поле | Тип | Описание |
|---|---|---|
| `intent` | string | `BOOK` \| `RESCHEDULE` \| `CANCEL` \| `UNSUPPORTED` |
| `specialty` | string\|null | `GP`, `CARDIOLOGIST`, `NEUROLOGIST`, `DENTIST`, `DERMATOLOGIST`, `OPHTHALMOLOGIST`, `GYNECOLOGIST`, `PSYCHOLOGIST`, `UNKNOWN_SPECIALTY` |
| `time` | string[] | фактор-токены: `T_TOMORROW`, `T_WED`, `T_MORNING`, `T_H10_30`, `T_NEXT_WEEK`, `T_UNKNOWN`, … (полный список — `ANDROID_INTEGRATION.md`) |
| `symptoms` | string[] | `SYMPTOM_HEADACHE`, `SYMPTOM_FEVER`, … |
| `needsClarification` | bool | модель просит уточнить |
| `raw` | string | исходная последовательность токенов (отладка) |

Бэкенд принимает и «сырой» ответ `{"raw": "<BOOK> ..."}` — парсер в него
превращает сам (`VoiceFrame.fromRaw`), поэтому формат поля `raw` —
минимальный обязательный уровень совместимости.

Генерация — жадная с автоматом слотов (`train/constrained.py`): вход
`<BOS> <USER> {токены} <ASSISTANT>`, паддинг до 128, logits с позиции `L-1`,
до 24 шагов, допустимые токены ограничены состоянием автомата — синтаксически
невалидный фрейм невозможен.

Известные ограничения модели (см. `models/failures_report.md`): часть
разговорных формулировок даёт `UNSUPPORTED`, возможны «фантомные» симптомы и
пропуск часового фактора времени. Бэкенд на это реагирует человекочитаемым
вопросом/подсказкой, а не падением.

Ошибки: `400` — пустой текст; `5xx` — модель недоступна → клиент получает
`502 AI_UNAVAILABLE`. Таймаут чтения — 30 с.

### Смена реализации этапа 2

```yaml
app:
  ai:
    parser:
      provider: http                      # stub | http
      url: ${PARSER_URL:http://localhost:8002/parse}
```

* `stub` — детерминированный фрейм из текста правилами (используется в unit-тестах и демо без Python);
* `http` — вызов сервиса выше.

## Проверка вручную

```bash
curl -s localhost:8001/health
curl -s localhost:8002/health
curl -s -X POST localhost:8002/parse -H 'Content-Type: application/json' \
  -d '{"text":"запишите к терапевту завтра"}'
# → {"intent":"BOOK","specialty":"GP","time":["T_TOMORROW"], ...}

curl -s -X POST localhost:8080/api/voice/parse \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"text":"запишите к терапевту завтра"}'
# → requestId, frame, resolved, candidates[], message
```

## Диагностика

* `502 {"error":"AI_UNAVAILABLE"}` — сервис этапа не отвечает (проверьте порт/URL);
* `401/403` — нет/недостаточно прав (нужен `Authorization: Bearer <JWT>`);
* журнал всех голосовых запросов: `GET /api/voice/requests` (ADMIN).
