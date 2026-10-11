#!/usr/bin/env bash
# Деплой бэкенда на сервер одной командой: сборка JAR -> scp -> запуск на сервере.
# Запускать С ЛОКАЛЬНОЙ машины:
#   ./deploy/deploy-local.sh          # поднять стек (сборка + запуск)
#   ./deploy/deploy-local.sh down     # остановить и ПОЛНОСТЬЮ очистить наш стек на сервере
# Адрес сервера можно переопределить:  SERVER=root@1.2.3.4 ./deploy/deploy-local.sh
#
# ПРО ПАРОЛЬ SSH: сервер пускает по паролю. Используется SSH-мультиплексирование,
# поэтому пароль спрашивается ОДИН раз за запуск (первое подключение), а scp и
# последующие ssh переиспользуют уже открытое соединение. Пароль нигде не хранится.
# Чтобы вообще убрать ввод пароля — установить ключ:  ssh-copy-id root@159.194.246.91
set -euo pipefail

cd "$(dirname "$0")"                     # .../VoiceAssistant/backend/deploy
BACKEND_DIR="$(cd .. && pwd)"            # .../VoiceAssistant/backend
SERVER="${SERVER:-root@159.194.246.91}"
REMOTE_DIR="${REMOTE_DIR:-/opt/clinic}"

# Одно SSH-соединение на весь запуск (пароль вводится один раз, далее переиспользуется)
mkdir -p "$HOME/.ssh" && chmod 700 "$HOME/.ssh"
SSH_OPTS=(
  -o ControlMaster=auto
  -o ControlPath="$HOME/.ssh/cm-%C"
  -o ControlPersist=10m
  -o ServerAliveInterval=30
)
SSH=(ssh "${SSH_OPTS[@]}")
SCP=(scp "${SSH_OPTS[@]}")

echo "== Подключение к ${SERVER} (пароль может быть запрошен ОДИН раз) =="

case "${1:-}" in
  down|--down|teardown)
    echo "== Останавливаем и очищаем наш стек на ${SERVER}:${REMOTE_DIR} =="
    "${SSH[@]}" "$SERVER" "mkdir -p '$REMOTE_DIR'"
    "${SCP[@]}" down.sh "$SERVER:$REMOTE_DIR/"
    "${SSH[@]}" "$SERVER" "chmod +x '$REMOTE_DIR/down.sh' && '$REMOTE_DIR/down.sh'"
    exit 0
    ;;
esac

echo "== 1/3 Сборка JAR =="
(cd "$BACKEND_DIR" && ./gradlew bootJar)

JAR="$BACKEND_DIR/build/libs/clinic-backend-0.1.0.jar"
[ -f "$JAR" ] || { echo "ОШИБКА: не найден $JAR"; exit 1; }

# Локальная NLU-модель из ai_model/clinic_lm/models (model.onnx + tokenizer.json — ПАРНЫЕ).
AI_MODEL_DIR="${AI_MODEL_DIR:-$BACKEND_DIR/../ai_model/clinic_lm/models}"
MODEL="$AI_MODEL_DIR/model.onnx"
TOKENIZER="$AI_MODEL_DIR/tokenizer.json"
[ -f "$MODEL" ] || { echo "ОШИБКА: не найден $MODEL"; exit 1; }
[ -f "$TOKENIZER" ] || { echo "ОШИБКА: не найден $TOKENIZER"; exit 1; }

echo "== 2/3 Копирование на ${SERVER}:${REMOTE_DIR} =="
"${SSH[@]}" "$SERVER" "mkdir -p '$REMOTE_DIR'"
"${SCP[@]}" "$JAR" Dockerfile docker-compose.yml .env up.sh down.sh "$SERVER:$REMOTE_DIR/"
"${SCP[@]}" "$MODEL" "$TOKENIZER" "$SERVER:$REMOTE_DIR/"

echo "== 3/3 Запуск на сервере =="
"${SSH[@]}" "$SERVER" "chmod +x '$REMOTE_DIR/up.sh' && '$REMOTE_DIR/up.sh'"
