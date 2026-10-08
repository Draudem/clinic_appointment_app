#!/usr/bin/env bash
# =============================================================================
# Smoke-тест бэкенда Clinic Voice Assistant — приёмка контракта для мобильного
# приложения и фронтенда.
#
# Использование:
#   ./smoke-test.sh [BASE_URL]        # по умолчанию http://localhost:8080/api
#
# Проверяется: health всех сервисов, JWT-авторизация и RBAC, каталоги
# (врача/специальности/расписание/сотрудники), CRUD записей, мобильный
# эндпоинт, голосовой конвейер (STT + парсер, если сервисы подняты).
#
# Зависимости: bash, curl, python3. Код возврата 0 — всё ок, 1 — есть провалы.
# =============================================================================
set -u

BASE="${1:-${BASE_URL:-http://localhost:8080/api}}"
API_ROOT="${BASE%/api}"
BODY=$(mktemp)
PASS=0; FAIL=0; SKIP=0
FAILED=()

green() { printf '\033[32m%s\033[0m\n' "$1"; }
red()   { printf '\033[31m%s\033[0m\n' "$1"; }
yellow(){ printf '\033[33m%s\033[0m\n' "$1"; }

section() { printf '\n== %s ==\n' "$1"; }
pass()    { PASS=$((PASS+1)); green "  ✓ $1"; }
fail()    { FAIL=$((FAIL+1)); FAILED+=("$1"); red   "  ✗ $1"; }
skip()    { SKIP=$((SKIP+1)); yellow "  ~ $1"; }

# req МЕТОД ПУТЬ [JSON-ТЕЛО] [ТОКЕН] -> CODE (+тело в $BODY)
req() {
  local method=$1 path=$2 data=${3:-} token=${4:-}
  local args=(-s -o "$BODY" -w "%{http_code}" -X "$method" "$BASE$path")
  [ -n "$data" ]  && args+=(-H 'Content-Type: application/json' -d "$data")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  CODE=$(curl -m 30 "${args[@]}")
}

# req_mp ПУТЬ ФАЙЛ ТОКЕН -> CODE (multipart audio=...)
req_mp() {
  local path=$1 file=$2 token=$3
  CODE=$(curl -m 60 -s -o "$BODY" -w "%{http_code}" -X POST "$BASE$path" \
    -H "Authorization: Bearer $token" -F "audio=@$file;type=audio/wav")
}

# jget ВЫРАЖЕНИЕ -> значение (python-выражение над d = JSON-ответа)
jget() { python3 -c "
import sys, json
d = json.load(open('$BODY'))
print($1)
" 2>/dev/null; }

assert_code() { # ожидаемый код описание
  if [ "$CODE" = "$1" ]; then pass "$2"; else
    fail "$2 — ожидали $1, получили $CODE: $(head -c 240 "$BODY")"
  fi
}

assert() { # условие(0=ok) описание
  if [ "$1" -eq 0 ]; then pass "$2"; else fail "$2"; fi
}

json_ok() { python3 -c "import json,sys; json.load(open('$BODY'))" 2>/dev/null; }

echo "Базовый адрес: $BASE"

# ---------------------------------------------------------------- health ----
section "1. Сервисы"
C=$(curl -m 5 -s -o "$BODY" -w "%{http_code}" "$API_ROOT/actuator/health")
[ "$C" = 200 ] && pass "бэкенд /actuator/health" || fail "бэкенд /actuator/health ($C)"

C=$(curl -m 5 -s -o "$BODY" -w "%{http_code}" "${STT_URL:-http://localhost:8001}/health")
if [ "$C" = 200 ]; then pass "STT-сервис /health"; STT_UP=1; else STT_UP=0; skip "STT-сервис недоступен ($C) — голосовой ввод через аудио не проверяется"; fi

C=$(curl -m 5 -s -o "$BODY" -w "%{http_code}" "${PARSER_URL:-http://localhost:8002}/health")
if [ "$C" = 200 ]; then pass "Парсер-сервис /health"; PARSER_UP=1; else PARSER_UP=0; skip "Парсер недоступен ($C) — голосовой разбор не проверяется"; fi

# ------------------------------------------------------------------ auth ----
section "2. Авторизация (JWT)"
STAMP=$(date +%s)
req POST /auth/register "{\"login\":\"smoke$STAMP\",\"password\":\"secret1\",\"fullName\":\"Smoke Test\"}"
assert_code 201 "регистрация нового пациента → 201"
NEW_TOKEN=$(jget 'd["token"]'); NEW_LOGIN=$(jget 'd["login"]')
[ -n "${NEW_TOKEN:-}" ] && pass "регистрация вернула JWT" || fail "регистрация не вернула token"

req POST /auth/register '{"login":"x","password":"y"}'
assert_code 400 "валидация регистрации (короткий логин) → 400"

req POST /auth/login '{"login":"admin","password":"admin123"}'
assert_code 200 "вход администратора → 200"
ADMIN_TOKEN=$(jget 'd["token"]'); ADMIN_ROLE=$(jget 'd["role"]'); ADMIN_ROLE_CODE=$(jget 'd["roleCode"]')
[ -n "${ADMIN_TOKEN:-}" ] && pass "вход администратора вернул JWT" || fail "нет token у администратора"
[ "$ADMIN_ROLE" = "администратор" ] && pass "роль отображается как «администратор» (ожидает фронтенд)" || fail "роль: '$ADMIN_ROLE' ≠ «администратор»"
[ "$ADMIN_ROLE_CODE" = "ADMIN" ] && pass "roleCode = ADMIN (ожидает мобильное)" || fail "roleCode: '$ADMIN_ROLE_CODE'"

req POST /auth/login '{"login":"admin","password":"wrong"}'
assert_code 401 "неверный пароль → 401"

req POST /auth/login '{"login":"patient","password":"patient123"}'
assert_code 200 "вход пациента → 200"
PATIENT_TOKEN=$(jget 'd["token"]')

req GET /auth/me "" "$PATIENT_TOKEN"
assert_code 200 "GET /auth/me с токеном → 200"

req GET /auth/me
assert_code 401 "GET /auth/me без токена → 401"

req GET /doctors
assert_code 401 "закрытый эндпоинт без токена → 401"

# ------------------------------------------------------------- каталоги -----
section "3. Каталоги и расписание (фронтенд)"
req GET /doctors "" "$ADMIN_TOKEN"
N=$(jget 'len(d)')
assert_code 200 "GET /doctors → 200"
[ "${N:-0}" -ge 12 ] && pass "врачей в БД: $N (≥ 12)" || fail "врачей: $N (< 12, сидер не отработал?)"

req GET /specialties "" "$ADMIN_TOKEN"
NS=$(jget 'len(d)')
assert_code 200 "GET /specialties → 200"
[ "${NS:-0}" -ge 12 ] && pass "специальностей: $NS (≥ 12)" || fail "специальностей: $NS"

req GET /schedule?date=2026-10-09 "" "$ADMIN_TOKEN"
assert_code 200 "GET /schedule?date → 200 (приёмы на дату)"

req GET "/calendar?year=2026&month=10" "" "$ADMIN_TOKEN"
NCAL=$(jget 'len(d)')
assert_code 200 "GET /calendar?year&month → 200"
[ "${NCAL:-0}" -eq 31 ] && pass "бейджи календаря: 31 день" || fail "дней в календаре: $NCAL (≠ 31)"

req GET /schedule/unique-times "" "$ADMIN_TOKEN"
assert_code 200 "GET /schedule/unique-times → 200"
OKFLAG=$(jget 'd.get("ok")')
[ "$OKFLAG" = "True" ] && pass "unique-times: {\"ok\":true}" || fail "unique-times: $(head -c 120 "$BODY")"

req GET /employees "" "$ADMIN_TOKEN"
NE=$(jget 'len(d) if isinstance(d,list) else -1')
assert_code 200 "GET /employees → 200"
FIRST_EMP=$(jget 'd[0]["fio"] and d[0]["specialty"] and d[0]["nextSlot"] is not None')
[ "${NE:-0}" -ge 12 ] && [ "$FIRST_EMP" = "True" ] \
  && pass "таблица сотрудников: $NE строк, колонки ФИО/специальность/ближайший приём" \
  || fail "employees: строк=$NE, колонки корректны=$FIRST_EMP"

# -------------------------------------------------------------- RBAC --------
section "4. Роли (RBAC)"
req GET /users "" "$PATIENT_TOKEN"
assert_code 403 "пациент на GET /users → 403"

req GET /users "" "$ADMIN_TOKEN"
assert_code 200 "администратор на GET /users → 200"
MASKED=$(jget 'all("•" in (u.get("password") or "") for u in d)')
[ "$MASKED" = "True" ] && pass "пароли в списке замаскированы (••••••)" || fail "пароли не маскируются"

# ------------------------------------------------------------- мобильное ----
section "5. Мобильный эндпоинт"
req GET "/mobile/doctors?days=14" "" "$PATIENT_TOKEN"
assert_code 200 "GET /mobile/doctors → 200"
DOC_ID=$(jget 'd[0]["id"]'); DOC_NAME=$(jget 'd[0]["name"]'); DOC_SPEC=$(jget 'd[0]["specialty"]')
SLOT_ID=$(jget 'next(s["id"] for s in d[0]["availableSlots"] if s["isAvailable"])' || true)
SLOT_DATE=$(jget 'next(s["date"] for s in d[0]["availableSlots"] if s["isAvailable"])' || true)
SLOT_TIME=$(jget 'next(s["time"] for s in d[0]["availableSlots"] if s["isAvailable"])' || true)
SHAPE=$(jget '"id" in d[0] and "name" in d[0] and "specialty" in d[0] and "availableSlots" in d[0]')
[ "$SHAPE" = "True" ] \
  && pass "формат совпадает с Models.kt (id,name,specialty,availableSlots), врач: $DOC_NAME" \
  || fail "формат mobile/doctors не совпадает с Models.kt: $(head -c 200 "$BODY")"
[ -n "${SLOT_ID:-}" ] && pass "найден свободный слот: $SLOT_ID" || fail "свободных слотов нет"

# ----------------------------------------------------------- appointments ---
section "6. Записи на приём (CRUD)"
req POST /appointments "{\"doctorId\":$DOC_ID,\"date\":\"$SLOT_DATE\",\"time\":\"00:11\"}" "$PATIENT_TOKEN"
assert_code 400 "несуществующее время → 400 с внятным message"

req POST /appointments "{\"doctorId\":$DOC_ID,\"date\":\"$SLOT_DATE\",\"time\":\"$SLOT_TIME\"}" "$PATIENT_TOKEN"
assert_code 201 "запись на свободный слот → 201"
AP_ID=$(jget 'd["id"]'); AP_STATUS=$(jget 'd["status"]')
[ -n "${AP_ID:-}" ] && pass "создана запись №$AP_ID (status=$AP_STATUS)" || fail "нет id у созданной записи"

req POST /appointments "{\"doctorId\":$DOC_ID,\"date\":\"$SLOT_DATE\",\"time\":\"$SLOT_TIME\"}" "$PATIENT_TOKEN"
assert_code 409 "повторная запись в занятый слот → 409 CONFLICT (слот блокируется)"

req GET /appointments "" "$PATIENT_TOKEN"
IN_LIST=$(jget "any(a['id']==int('$AP_ID') for a in d)")
[ "$IN_LIST" = "True" ] && pass "запись видна в списке пациента" || fail "записи нет в GET /appointments"

req DELETE "/appointments/$AP_ID" "" "$PATIENT_TOKEN"
assert_code 204 "отмена записи → 204"

req GET /appointments "" "$PATIENT_TOKEN"
GONE=$(jget "not any(a['id']==int('$AP_ID') for a in d)")
[ "$GONE" = "True" ] && pass "после отмены запись не вернулась" || fail "запись всё ещё в списке"

# ---------------------------------------------------------------- voice -----
section "7. Голосовой конвейер"
if [ "$PARSER_UP" = 1 ]; then
  req POST /voice/parse '{"text":"запишите к терапевту завтра"}' "$PATIENT_TOKEN"
  assert_code 200 "POST /voice/parse (текст → фрейм) → 200"
  INTENT=$(jget 'd["frame"]["intent"]'); SPEC=$(jget 'd["frame"]["specialty"]')
  [ "$INTENT" = "BOOK" ] && [ "$SPEC" = "GP" ] \
    && pass "фрейм разобран: intent=$INTENT specialty=$SPEC (ONNX-модель)" \
    || fail "неожиданный фрейм: intent=$INTENT specialty=$SPEC"

  req POST /voice/parse '{"text":""}' "$PATIENT_TOKEN"
  assert_code 400 "пустой текст → 400"
else
  skip "парсер недоступен — /voice/parse не проверен"
fi

if [ "$STT_UP" = 1 ]; then
  TMPWAV=$(mktemp --suffix=.wav); head -c 4096 /dev/urandom > "$TMPWAV"
  req_mp /voice/transcribe "$TMPWAV" "$PATIENT_TOKEN"
  assert_code 200 "POST /voice/transcribe (аудио → текст) → 200"
  VTT=$(jget 'd.get("text","")')
  [ -n "$VTT" ] && pass "STT вернул текст: «$VTT»" || fail "STT не вернул text"

  if [ "$PARSER_UP" = 1 ]; then
    req_mp /voice/command "$TMPWAV" "$PATIENT_TOKEN"
    assert_code 200 "POST /voice/command (полный конвейер) → 200"
    MSG=$(jget 'd.get("message","")')
    [ -n "$MSG" ] && pass "конвейер ответил: «$MSG»" || fail "нет message в ответе command"
  fi
  rm -f "$TMPWAV"
else
  skip "STT недоступен — аудио-эндпоинты не проверены"
fi

req GET /voice/requests "" "$ADMIN_TOKEN"
assert_code 200 "журнал голосовых запросов (админ) → 200"
req GET /voice/requests "" "$PATIENT_TOKEN"
assert_code 403 "журнал недоступен пациенту → 403"

# --------------------------------------------------------------- swagger ----
section "8. Документация"
C=$(curl -m 5 -s -o /dev/null -w "%{http_code}" "$API_ROOT/swagger-ui.html")
case "$C" in 200|302) pass "Swagger UI отвечает ($C): $API_ROOT/swagger-ui.html";;
              *) fail "Swagger UI недоступен ($C)";; esac

# -------------------------------------------------------------- итог --------
printf '\n'
echo "----------------------------------------"
echo "Итого: $PASS ок, $FAIL провалов, $SKIP пропущено"
if [ "$FAIL" -gt 0 ]; then
  printf 'Провалы:\n'
  for f in "${FAILED[@]}"; do red "  - $f"; done
  exit 1
fi
green "Все проверки пройдены — контракт для мобильного/фронтенда работает."
