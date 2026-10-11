#!/usr/bin/env bash
# Остановка локального бэкенда и уборка следов запуска (парная к deploy/dev-up.sh).
# Запускать НА СВОЕЙ машине из корня бэкенда:
#   ./deploy/dev-down.sh              # остановить бэк + убрать созданное НАМИ
#   ./deploy/dev-down.sh --drop-db    # дополнительно удалить БД, ТОЛЬКО если её создал dev-up.sh
#
# БЕЗОПАСНОСТЬ ДЛЯ ОКРУЖЕНИЯ:
#  - Docker: сносит только ресурсы нашего проекта (по умолчанию clinic-local): <project>-db/-db-data/-net.
#    Базовый образ postgres:16 и любые чужие контейнеры/тома/сети/образы не трогаются. Глобальные prune не используются.
#  - Postgres: бэк просто останавливается; ваша БД НЕ удаляется (кроме --drop-db и только если её создали мы).
#  - Удаляется лишь каталог backend/.local-run (pid/лог/состояние).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
STATE_DIR="$BACKEND_DIR/.local-run"
STATE_FILE="$STATE_DIR/state.env"
PID_FILE="$STATE_DIR/backend.pid"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.local.yml"

DROP_DB=0
for arg in "$@"; do
  case "$arg" in
    --drop-db) DROP_DB=1 ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "неизвестный аргумент: $arg (см. --help)" >&2; exit 2 ;;
  esac
done

say() { printf '\n== %s ==\n' "$*"; }

RESOLVED_MODE=""; PROJECT="clinic-local"; PG_HOST="localhost"; PG_PORT=""; PG_DB="clinic"
PG_USER="clinic"; PG_PASS="clinic"; DB_CREATED=0; ROLE_CREATED=0; SERVER_PORT="8080"
if [ -f "$STATE_FILE" ]; then
  # shellcheck disable=SC1090
  . "$STATE_FILE"
  echo "состояние запуска: $STATE_FILE"
else
  echo "файла состояния нет — работаю по умолчанию (бэк не запускался через dev-up.sh?)"
fi

say "Останавливаю бэкенд"
if [ -f "$PID_FILE" ]; then
  pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
    if kill -0 "$pid" 2>/dev/null; then kill -9 "$pid" 2>/dev/null || true; fi
    echo "процесс $pid остановлен"
  else
    echo "процесс уже не запущен"
  fi
else
  echo "pid-файл не найден — ничего не останавливаю"
fi

case "$RESOLVED_MODE" in
  docker)
    say "Убираю докер-проект '$PROJECT' (только наше)"
    if command -v docker >/dev/null 2>&1; then
      PROJECT="$PROJECT" docker compose -p "$PROJECT" -f "$COMPOSE_FILE" down -v --rmi local || true
      leftover=0
      docker ps -a  --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Names}}' | grep -q . && { echo "! остались контейнеры"; leftover=1; }
      docker volume ls --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Name}}' | grep -q . && { echo "! остались тома"; leftover=1; }
      docker network ls --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Name}}' | grep -q . && { echo "! остались сети"; leftover=1; }
      [ "$leftover" -eq 0 ] && echo "наших контейнеров/томов/сетей не осталось"
    else
      echo "docker не найден — пропускаю"
    fi
    ;;
  postgres)
    if [ "$DROP_DB" = 1 ]; then
      if [ "$DB_CREATED" = 1 ]; then
        say "Удаляю созданную НАМИ БД '$PG_DB'"
        if command -v psql >/dev/null 2>&1; then
          PGPASSWORD="$PG_PASS" psql -h "$PG_HOST" -p "${PG_PORT:-5432}" -U "$PG_USER" -d postgres -q \
            -c "DROP DATABASE IF EXISTS \"$PG_DB\";" 2>/dev/null || echo "не удалось удалить БД (нет прав?) — пропускаю"
          if [ "$ROLE_CREATED" = 1 ]; then
            PGPASSWORD="$PG_PASS" psql -h "$PG_HOST" -p "${PG_PORT:-5432}" -U "$PG_USER" -d postgres -q \
              -c "DROP ROLE IF EXISTS \"$PG_USER\";" 2>/dev/null || true
          fi
        else
          echo "psql не найден — БД не удаляю"
        fi
      else
        echo "БД '$PG_DB' создавали не мы — НЕ удаляю (это твоё окружение)"
      fi
    else
      echo "локальный Postgres оставлен как есть (удалить созданную нами БД: --drop-db)"
    fi
    ;;
  *)
    echo "режим БД неизвестен — БД не трогаю"
    ;;
esac

say "Уборка локальных файлов запуска"
rm -rf "$STATE_DIR"
echo "готово: следов запуска в $BACKEND_DIR/.local-run не осталось"
