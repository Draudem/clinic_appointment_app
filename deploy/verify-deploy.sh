#!/usr/bin/env bash
# Проверка развёрнутого стека после деплоя: контейнеры, STT, порты, сквозная связь.
#
# ЗАПУСК (на машине, где развёрнут стек, из папки deploy/):
#   ./verify-deploy.sh
#
# Что проверяет и что означает каждая неудача — написано рядом с проверкой,
# чтобы не приходилось гадать по логам.
set -uo pipefail

cd "$(dirname "$0")"

HTTP_PORT="${HTTP_PORT:-8090}"
AUDIO_PORT="${AUDIO_PORT:-8091}"
PUBLIC="${STT_PUBLIC_READ:-true}"
ok=0
fail=0

say()  { printf '\n== %s\n' "$1"; }
good() { printf '  [OK]   %s\n' "$1"; }
bad()  { printf '  [FAIL] %s\n' "$1"; ok=$((ok+1)); fail=$((fail+1)); }

# --------------------------------------------------------------------------
say "1/5 Контейнеры"
if ! command -v docker >/dev/null 2>&1; then
  bad "docker не найден"
elif ! docker compose ps >/dev/null 2>/tmp/.verr; then
  if grep -qi "permission denied" /tmp/.verr 2>/dev/null; then
    bad "нет доступа к сокету Docker. Пользователь не в группе docker.
       Исправить:  sudo usermod -aG docker \$USER
       Затем ПЕРЕЛОГИНИТЬСЯ (иначе группа не применится) и запустить скрипт заново."
  else
    bad "docker недоступен: $(head -1 /tmp/.verr 2>/dev/null)"
  fi
else
  docker compose ps
  if docker compose ps --status=running 2>/dev/null | grep -q backend; then
    good "контейнер backend работает"
  else
    bad "контейнер backend не запущен — смотри: docker compose logs backend --tail 80"
  fi
fi

# --------------------------------------------------------------------------
say "2/5 HTTP-доступность (порт $HTTP_PORT)"
HEALTH=$(curl -fsS -m 5 "http://localhost:${HTTP_PORT}/api/health" 2>/dev/null || true)
if [ -n "$HEALTH" ]; then
  good "бэкенд отвечает: $HEALTH"
else
  bad "бэкенд не отвечает на порту $HTTP_PORT"
fi

# --------------------------------------------------------------------------
say "3/5 STT и порт AudioSocket ($AUDIO_PORT)"
if [ "$PUBLIC" = "true" ]; then
  STT=$(curl -fsS -m 5 "http://localhost:${HTTP_PORT}/api/stt/public/status" 2>/dev/null || true)
else
  TOKEN=$(curl -fsS -m 5 -X POST "http://localhost:${HTTP_PORT}/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"login":"admin","password":"admin123"}' 2>/dev/null \
    | sed -n 's/.*"token" *: *"\([^"]*\)".*/\1/p')
  [ -n "$TOKEN" ] && STT=$(curl -fsS -m 5 "http://localhost:${HTTP_PORT}/api/stt/status" \
    -H "Authorization: Bearer $TOKEN" 2>/dev/null || true)
fi

if [ -z "$STT" ]; then
  bad "STT-статус недоступен (проверь app.stt.public-read в .env)"
else
  echo "  $STT"
  case "$STT" in
    *'"modelReady":true'*) good "модель распознавания загружена" ;;
    *'"modelReady":false'*) bad "модель НЕ загружена — порт $AUDIO_PORT не поднимется.
       Чаще всего нет libonnxruntime.so (LD_LIBRARY_PATH) или файлов модели в образе.
       Смотри: docker compose logs backend | grep -i 'stt\|модель'" ;;
  esac
  case "$STT" in
    *'"listening":true'*) good "порт AudioSocket слушает" ;;
    *'"listening":false'*) bad "порт $AUDIO_PORT не слушается — Asterisk не сможет подключиться" ;;
  esac
fi

if command -v ss >/dev/null 2>&1; then
  if ss -ltn 2>/dev/null | grep -q ":${AUDIO_PORT}\b"; then
    good "порт $AUDIO_PORT опубликован наружу"
  else
    bad "порт $AUDIO_PORT не слушается снаружи"
  fi
fi

# --------------------------------------------------------------------------
say "4/5 Доступ снаружи (как увидит напарник)"
HOST_IP=$(hostname -I 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9]+\.' | head -1)
HOST_IP="${STT_ADVERTISED_HOST:-$HOST_IP}"
if [ -z "$HOST_IP" ]; then
  bad "не удалось определить IP машины"
else
  if curl -fsS -m 5 "http://${HOST_IP}:${HTTP_PORT}/api/health" >/dev/null 2>&1; then
    good "HTTP доступен по адресу ${HOST_IP}:${HTTP_PORT}"
  else
    bad "HTTP недоступен по ${HOST_IP}:${HTTP_PORT} — проверь файрвол"
  fi
  echo
  echo "  Команда для проверки связи с другого компьютера:"
  echo "    python3 -c \"import urllib.request as u; exec(u.urlopen('http://${HOST_IP}:${HTTP_PORT}/api/stt/public/client').read())\""
fi

# --------------------------------------------------------------------------
say "5/5 Сквозная проверка связи (нужен только python3)"
if command -v python3 >/dev/null 2>&1; then
  # Показываем только осмысленные строки: traceback urllib'а в выводе не нужен.
  python3 -c "import urllib.request as u; exec(u.urlopen('http://localhost:${HTTP_PORT}/api/stt/public/client').read())" -- --no-mic 2>&1 \
    | grep -E '\[OK\]|\[FAIL\]|\[warn\]|^==|^ИТОГ|распознано'
  RC=${PIPESTATUS[0]}
  case $RC in
    0) good "связь работает, распознавание отвечает" ;;
    2) bad "связь есть, но распознавание не ответило" ;;
    *) bad "проверка связи не прошла (код $RC)" ;;
  esac
else
  printf '  python3 не найден — этот шаг пропущен\n'
fi

# --------------------------------------------------------------------------
printf '\n%s\n' "===================================================="
if [ "$fail" -eq 0 ]; then
  printf 'ИТОГ: ВСЁ РАБОТАЕТ.\n'
  printf 'Asterisk подключается на:\n    AudioSocket(%s:%s)\n' "${HOST_IP:?}" "$AUDIO_PORT"
  printf '  пройдено проверок: %s, провалов: %s\n' "$ok" "$fail"
  printf '%s\n' "===================================================="
  exit 0
else
  printf 'ИТОГ: ПРОВАЛЕНО ПРОВЕРОК: %s из %s — смотрите [FAIL] выше.\n' "$fail" "$((ok+fail))"
  printf '%s\n' "===================================================="
  exit 2
fi