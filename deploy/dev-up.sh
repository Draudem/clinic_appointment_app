#!/usr/bin/env bash
# Локальный запуск бэкенда клиники ОДНОЙ командой (для разработки/интеграции фронта).
# Запускать НА СВОЕЙ машине из корня бэкенда (там, где gradlew):
#   ./deploy/dev-up.sh                 # авто: локальный Postgres, иначе Docker
#   ./deploy/dev-up.sh --db=postgres   # только локальный Postgres
#   ./deploy/dev-up.sh --db=docker     # только Docker (postgres:16 в проекте clinic-local)
#   ./deploy/dev-up.sh -f              # в переднем плане (Ctrl+C для остановки)
#
# Опции: --port=N  --pg-host=H  --pg-port=N  --pg-db=NAME  --pg-user=U  --pg-pass=P  --project=NAME
#
# БЕЗОПАСНОСТЬ ДЛЯ ОКРУЖЕНИЯ: скрипт трогает только
#  - свой докер-проект (по умолчанию clinic-local) и его ресурсы: <project>-db / -db-data / -net;
#  - каталог backend/.local-run (pid/лог/состояние);
#  - НИЧЕГО чужого: глобальные docker prune, чужие контейнеры/тома/сети/образы и ваши БД не затрагиваются.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
STATE_DIR="$BACKEND_DIR/.local-run"
STATE_FILE="$STATE_DIR/state.env"
LOG_FILE="$STATE_DIR/backend.log"
PID_FILE="$STATE_DIR/backend.pid"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.local.yml"

DB_MODE="auto"                       # auto | postgres | docker
FOREGROUND=0
SERVER_PORT="${SERVER_PORT:-8080}"
PG_HOST="${DB_HOST:-localhost}"
PG_PORT="${DB_PORT:-}"
PG_DB="${DB_NAME:-clinic}"
PG_USER="${DB_USER:-clinic}"
PG_PASS="${DB_PASSWORD:-clinic}"
PROJECT="${PROJECT:-clinic-local}"

RESOLVED_MODE=""
DB_CREATED=0
ROLE_CREATED=0

say() { printf '\n== %s ==\n' "$*"; }
die() { printf '\nОШИБКА: %s\n' "$*" >&2; exit 1; }

port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# --- аргументы ---
for arg in "$@"; do
  case "$arg" in
    --db=*)     DB_MODE="${arg#*=}" ;;
    -f|--foreground) FOREGROUND=1 ;;
    --port=*)   SERVER_PORT="${arg#*=}" ;;
    --pg-host=*) PG_HOST="${arg#*=}" ;;
    --pg-port=*) PG_PORT="${arg#*=}" ;;
    --pg-db=*)  PG_DB="${arg#*=}" ;;
    --pg-user=*) PG_USER="${arg#*=}" ;;
    --pg-pass=*) PG_PASS="${arg#*=}" ;;
    --project=*) PROJECT="${arg#*=}" ;;
    -h|--help)  sed -n '2,17p' "$0"; exit 0 ;;
    *) die "неизвестный аргумент: $arg (см. --help)" ;;
  esac
done

case "$DB_MODE" in auto|postgres|docker) ;; *) die "неизвестный --db=$DB_MODE (ожидается auto|postgres|docker)";; esac

# --- проверки окружения ---
say "Проверки"
command -v java >/dev/null 2>&1 || die "Java не найдена. Нужна Java 21."
java -version 2>&1 | head -1 | grep -Eq '"(21|2[2-9]|[3-9][0-9])' \
  || echo "ВНИМАНИЕ: ожидается Java 21, обнаружено: $(java -version 2>&1 | head -1)"
[ -x "$BACKEND_DIR/gradlew" ] || die "не найден $BACKEND_DIR/gradlew (запускай из корня бэкенда)"

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE" 2>/dev/null)" 2>/dev/null; then
  die "бэкенд уже запущен (pid $(cat "$PID_FILE"), порт $SERVER_PORT). Останови: ./deploy/dev-down.sh"
fi

port_in_use "$SERVER_PORT" && die "порт $SERVER_PORT занят. Укажи другой: --port=8081 (и поменяй BASE_URL во фронте)"

MODEL="${NLU_MODEL_PATH:-$BACKEND_DIR/../ai_model/clinic_lm/models/model.onnx}"
TOK="${NLU_TOKENIZER_PATH:-$BACKEND_DIR/../ai_model/clinic_lm/models/tokenizer.json}"
[ -f "$MODEL" ] || die "не найден model.onnx ($MODEL).
Скопируй репозиторий модели (ветка main): git clone -b main https://github.com/haitaks/clinic_lm.git ../ai_model/clinic_lm
либо укажи путь: NLU_MODEL_PATH=/путь/model.onnx ./deploy/dev-up.sh"
[ -f "$TOK" ] || die "не найден tokenizer.json ($TOK).
Нужны ОБА файла (model.onnx и tokenizer.json — парные). См. README §9."
echo "Java, gradlew, порт $SERVER_PORT и файлы модели — ок"

# --- работа с БД ---
psql_ok() { # host port user db
  command -v psql >/dev/null 2>&1 || return 1
  PGPASSWORD="$PG_PASS" PGCONNECT_TIMEOUT=3 psql -h "$1" -p "$2" -U "$3" -d "$4" -tAc 'select 1' >/dev/null 2>&1
}
pg_ready() { # host port
  if command -v pg_isready >/dev/null 2>&1; then pg_isready -h "$1" -p "$2" -q; else port_in_use "$2"; fi
}
docker_ready() { command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; }

try_postgres() {
  local port="${PG_PORT:-5432}"
  pg_ready "$PG_HOST" "$port" || { echo "локальный Postgres не отвечает на $PG_HOST:$port"; return 1; }

  if [ ! -x "$(command -v psql 2>/dev/null || true)" ]; then
    echo "psql не найден — не могу проверить/создать БД; предполагаю, что '$PG_DB' существует и доступна '$PG_USER'"
    RESOLVED_MODE="postgres"; PG_PORT="$port"; return 0
  fi

  if psql_ok "$PG_HOST" "$port" "$PG_USER" "$PG_DB"; then
    echo "использую существующую БД '$PG_DB' на $PG_HOST:$port (создавать не нужно)"
    RESOLVED_MODE="postgres"; PG_PORT="$port"; DB_CREATED=0; ROLE_CREATED=0; return 0
  fi

  # Пробуем создать БД/роль: сначала как PG_USER в служебной БД, иначе через sudo -u postgres
  local base_cmd="" created_any=0
  if psql_ok "$PG_HOST" "$port" "$PG_USER" postgres; then
    base_cmd="direct"
  elif command -v sudo >/dev/null 2>&1 && sudo -n -u postgres psql -tAc 'select 1' >/dev/null 2>&1; then
    base_cmd="sudo"
  fi

  if [ -z "$base_cmd" ]; then
    echo "не удалось подключиться к Postgres, чтобы создать БД"
    return 1
  fi

  echo "создаю роль/БД '$PG_USER'/'$PG_DB' ($base_cmd)"
  if [ "$base_cmd" = "direct" ]; then
    PGPASSWORD="$PG_PASS" psql -h "$PG_HOST" -p "$port" -U "$PG_USER" -d postgres -q \
      -c "CREATE ROLE \"$PG_USER\" LOGIN PASSWORD '$PG_PASS';" 2>/dev/null && ROLE_CREATED=1 || true
    PGPASSWORD="$PG_PASS" psql -h "$PG_HOST" -p "$port" -U "$PG_USER" -d postgres -q \
      -c "CREATE DATABASE \"$PG_DB\" OWNER \"$PG_USER\";" 2>/dev/null && DB_CREATED=1 || true
  else
    sudo -u postgres psql -q \
      -c "CREATE ROLE \"$PG_USER\" LOGIN PASSWORD '$PG_PASS';" 2>/dev/null && ROLE_CREATED=1 || true
    sudo -u postgres psql -q \
      -c "CREATE DATABASE \"$PG_DB\" OWNER \"$PG_USER\";" 2>/dev/null && DB_CREATED=1 || true
  fi

  if psql_ok "$PG_HOST" "$port" "$PG_USER" "$PG_DB"; then
    echo "БД '$PG_DB' готова"
    RESOLVED_MODE="postgres"; PG_PORT="$port"; return 0
  fi
  echo "БД так и не стала доступна"
  return 1
}

setup_docker() {
  docker_ready || { echo "Docker недоступен (нет docker или демон не отвечает)"; return 1; }
  [ -f "$COMPOSE_FILE" ] || die "не найден $COMPOSE_FILE"

  # Конфликт имён: чужие ресурсы с нашими именами не трогаем
  if docker inspect "${PROJECT}-db" >/dev/null 2>&1 \
     && [ "$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "${PROJECT}-db" 2>/dev/null)" != "$PROJECT" ]; then
    die "контейнер '${PROJECT}-db' уже существует и принадлежит НЕ нам — не трогаю. Задай другое имя проекта: --project=clinic-local-2"
  fi
  if docker volume inspect "${PROJECT}-db-data" >/dev/null 2>&1 \
     && [ "$(docker volume inspect -f '{{index .Labels "com.docker.compose.project"}}' "${PROJECT}-db-data" 2>/dev/null)" != "$PROJECT" ]; then
    die "том '${PROJECT}-db-data' уже существует и принадлежит НЕ нам — не трогаю. Задай --project=clinic-local-2"
  fi
  if docker network inspect "${PROJECT}-net" >/dev/null 2>&1 \
     && [ "$(docker network inspect -f '{{index .Labels "com.docker.compose.project"}}' "${PROJECT}-net" 2>/dev/null)" != "$PROJECT" ]; then
    die "сеть '${PROJECT}-net' уже существует и принадлежит НЕ нам — не трогаю. Задай --project=clinic-local-2"
  fi

  # Свободный порт на хосте (по умолчанию от 5434)
  local p="${PG_PORT:-5434}"
  while port_in_use "$p"; do p=$((p + 1)); [ "$p" -gt 5534 ] && die "не нашёл свободный порт для БД на хосте"; done
  PG_PORT="$p"

  echo "поднимаю postgres:16 в проекте '$PROJECT' (127.0.0.1:$PG_PORT)"
  PROJECT="$PROJECT" DB_NAME="$PG_DB" DB_USER="$PG_USER" DB_PASSWORD="$PG_PASS" DB_PORT="$PG_PORT" \
    docker compose -p "$PROJECT" -f "$COMPOSE_FILE" up -d

  echo "жду готовности БД..."
  local i
  for i in $(seq 1 60); do
    docker exec "${PROJECT}-db" pg_isready -U "$PG_USER" -d "$PG_DB" >/dev/null 2>&1 && break
    [ "$i" -eq 60 ] && die "БД не поднялась за 60с. Логи: docker logs ${PROJECT}-db"
    sleep 1
  done
  PG_HOST="127.0.0.1"; RESOLVED_MODE="docker"; DB_CREATED=0; ROLE_CREATED=0
  echo "БД готова"
  return 0
}

say "База данных (режим: $DB_MODE)"
case "$DB_MODE" in
  postgres) try_postgres || die "не удалось подготовить локальный Postgres (см. сообщения выше). Вариант: --db=docker";;
  docker)   setup_docker   || die "не удалось поднять Docker-БД";;
  auto)
    if try_postgres; then :;
    elif setup_docker; then echo "(локальный Postgres недоступен — использован Docker)";
    else die "нет ни доступного локального Postgres, ни Docker. Запусти один из них."; fi ;;
esac
[ -n "$RESOLVED_MODE" ] || die "режим БД не определён"

# --- сборка и запуск ---
export SERVER_PORT DB_HOST="$PG_HOST" DB_PORT="$PG_PORT" DB_NAME="$PG_DB" DB_USER="$PG_USER" DB_PASSWORD="$PG_PASS"
export NLU_MODEL_PATH="$MODEL" NLU_TOKENIZER_PATH="$TOK"
export JWT_SECRET="${JWT_SECRET:-dev-secret-change-me-0123456789abcdefghijklmnop}"

say "Сборка JAR"
( cd "$BACKEND_DIR" && ./gradlew -q bootJar )
JAR="$(ls -1 "$BACKEND_DIR"/build/libs/clinic-backend-*.jar 2>/dev/null | grep -v -- '-plain.jar' | head -1)"
[ -f "$JAR" ] || die "JAR не найден после сборки"

mkdir -p "$STATE_DIR"
cat > "$STATE_FILE" <<EOF
RESOLVED_MODE=$RESOLVED_MODE
PROJECT=$PROJECT
PG_HOST=$PG_HOST
PG_PORT=$PG_PORT
PG_DB=$PG_DB
PG_USER=$PG_USER
PG_PASS=$PG_PASS
DB_CREATED=$DB_CREATED
ROLE_CREATED=$ROLE_CREATED
SERVER_PORT=$SERVER_PORT
EOF
chmod 600 "$STATE_FILE"

if [ "$FOREGROUND" = 1 ]; then
  say "Запуск в переднем плане (Ctrl+C для остановки)"
  exec java -jar "$JAR"
fi

say "Запуск бэкенда в фоне"
nohup java -jar "$JAR" >"$LOG_FILE" 2>&1 &
echo $! >"$PID_FILE"

say "Жду /api/health"
ok=0
for i in $(seq 1 90); do
  if curl -fsS -m 2 "http://localhost:${SERVER_PORT}/api/health" >/dev/null 2>&1; then ok=1; break; fi
  kill -0 "$(cat "$PID_FILE")" 2>/dev/null || break
  sleep 1
done
if [ "$ok" != 1 ]; then
  echo "Бэкенд не ответил. Последние строки лога:"; tail -n 40 "$LOG_FILE" || true
  die "запуск не удался (лог: $LOG_FILE)"
fi

printf '\nГОТОВО.\n  health:  %s\n  Swagger: http://localhost:%s/swagger-ui/index.html\n  лог:     %s\n  стоп:    ./deploy/dev-down.sh\n' \
  "$(curl -fsS "http://localhost:${SERVER_PORT}/api/health")" "$SERVER_PORT" "$LOG_FILE"
