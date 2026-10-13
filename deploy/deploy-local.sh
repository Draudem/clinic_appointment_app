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

echo "== 1/4 Сборка JAR =="
(cd "$BACKEND_DIR" && ./gradlew bootJar)

JAR="$BACKEND_DIR/build/libs/clinic-backend-0.1.0.jar"
[ -f "$JAR" ] || { echo "ОШИБКА: не найден $JAR"; exit 1; }

# Локальная NLU-модель из ai_model/clinic_lm/models (model.onnx + tokenizer.json — ПАРНЫЕ).
# ВАЖНО: это ОТДЕЛЬНЫЙ репозиторий (haitaks/clinic_lm), в clinic_appointment_app его нет.
# Путь ../ai_model/... рассчитан на то, что оба репозитория лежат рядом в VoiceAssistant/.
AI_MODEL_DIR="${AI_MODEL_DIR:-$BACKEND_DIR/../ai_model/clinic_lm/models}"
MODEL="$AI_MODEL_DIR/model.onnx"
TOKENIZER="$AI_MODEL_DIR/tokenizer.json"
if [ ! -f "$MODEL" ] || [ ! -f "$TOKENIZER" ]; then
  echo "ОШИБКА: не найдены файлы NLU-модели: $MODEL, $TOKENIZER"
  echo
  echo "Модель лежит в ОТДЕЛЬНОМ репозитории и в clinic_appointment_app не входит."
  echo "Склонировать рядом с бэкендом:"
  echo "    git clone -b main https://github.com/haitaks/clinic_lm.git $BACKEND_DIR/../ai_model/clinic_lm"
  echo
  echo "Либо укажи свой путь:"
  echo "    AI_MODEL_DIR=/путь/к/models ./deploy/deploy-local.sh"
  exit 1
fi

# STT-модель GigaAM и нативная библиотека для неё.
# Они НЕ лежат в репозитории (227 МБ и 15 МБ), поэтому при отсутствии качаются сами.
# Оба скрипта идемпотентны: если файлы уже есть, повторный деплой ничего не качает.
STT_MODEL_DIR="${STT_MODEL_DIR:-$BACKEND_DIR/models/stt/sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19}"
STT_MODEL="$STT_MODEL_DIR/model.int8.onnx"
STT_TOKENS="$STT_MODEL_DIR/tokens.txt"
STT_LIB="$BACKEND_DIR/lib/libonnxruntime.so"

if [ ! -f "$STT_MODEL" ] || [ ! -f "$STT_TOKENS" ]; then
  echo "== 2/4 Скачиваем STT-модель GigaAM (227 МБ, делается один раз) =="
  "$BACKEND_DIR/deploy/fetch-stt-model.sh"
fi
if [ ! -f "$STT_LIB" ]; then
  echo "== 2/4 Скачиваем libonnxruntime для STT (15 МБ, делается один раз) =="
  "$BACKEND_DIR/deploy/fetch-stt-libs.sh"
fi
[ -f "$STT_MODEL" ]  || { echo "ОШИБКА: не найден $STT_MODEL"; exit 1; }
[ -f "$STT_TOKENS" ] || { echo "ОШИБКА: не найден $STT_TOKENS"; exit 1; }
[ -f "$STT_LIB" ]    || { echo "ОШИБКА: не найден $STT_LIB"; exit 1; }

echo "== 3/4 Копирование на ${SERVER}:${REMOTE_DIR} =="
"${SSH[@]}" "$SERVER" "mkdir -p '$REMOTE_DIR'"
"${SCP[@]}" "$JAR" Dockerfile docker-compose.yml .env up.sh down.sh "$SERVER:$REMOTE_DIR/"
"${SCP[@]}" "$MODEL" "$TOKENIZER" "$SERVER:$REMOTE_DIR/"
"${SCP[@]}" "$STT_MODEL" "$STT_TOKENS" "$STT_LIB" "$SERVER:$REMOTE_DIR/"

echo "== 4/4 Запуск на сервере =="
"${SSH[@]}" "$SERVER" "chmod +x '$REMOTE_DIR/up.sh' && '$REMOTE_DIR/up.sh'"
