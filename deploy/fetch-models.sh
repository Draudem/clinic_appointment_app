#!/usr/bin/env bash
set -euo pipefail

# Скрипт автоматического скачивания всех моделей (STT, TTS, NLU) и проверки данных.
# Модели большие, поэтому они не лежат в Git.
# Запуск из корня проекта: ./deploy/fetch-models.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODELS_DIR="$ROOT_DIR/models"
DATA_DIR="$ROOT_DIR/data"

mkdir -p "$MODELS_DIR" "$DATA_DIR"

echo "=== 1. Проверка и скачивание моделей в $MODELS_DIR ==="

# ------------------------------------------------------------------------------
# NLU Модель (ONNX) + Токенизатор
# ------------------------------------------------------------------------------
NLU_DIR="$MODELS_DIR/nlu"
mkdir -p "$NLU_DIR"

if [ ! -f "$NLU_DIR/model.onnx" ]; then
    echo "-> Скачиваем NLU модель (model.onnx)..."
    wget -q --show-progress "https://github.com/haitaks/clinic_lm/releases/download/v2/model.onnx" -O "$NLU_DIR/model.onnx"
else
    echo "-> NLU модель (model.onnx) уже на месте."
fi

if [ ! -f "$NLU_DIR/tokenizer_v2.json" ]; then
    echo "-> Скачиваем NLU токенизатор (tokenizer_v2.json)..."
    wget -q --show-progress "https://github.com/haitaks/clinic_lm/releases/download/v3/tokenizer_v2.json" -O "$NLU_DIR/tokenizer_v2.json"
else
    echo "-> NLU токенизатор (tokenizer_v2.json) уже на месте."
fi

# ------------------------------------------------------------------------------
# GigaAM STT (Распознавание речи)
# ------------------------------------------------------------------------------
STT_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19.tar.bz2"
STT_DIR="$MODELS_DIR/stt/sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19"

if [ ! -d "$STT_DIR" ]; then
    echo "-> Скачиваем GigaAM STT..."
    wget -q --show-progress "$STT_URL" -O "$MODELS_DIR/stt.tar.bz2"
    mkdir -p "$MODELS_DIR/stt"
    tar -xjf "$MODELS_DIR/stt.tar.bz2" -C "$MODELS_DIR/stt"
    rm "$MODELS_DIR/stt.tar.bz2"
else
    echo "-> GigaAM STT уже на месте."
fi

# ------------------------------------------------------------------------------
# Piper TTS (Синтез речи)
# ------------------------------------------------------------------------------
TTS_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-irina-medium-int8.tar.bz2"
TTS_DIR="$MODELS_DIR/tts/vits-piper-ru_RU-irina-medium-int8"

if [ ! -d "$TTS_DIR" ]; then
    echo "-> Скачиваем Piper TTS (irina)..."
    wget -q --show-progress "$TTS_URL" -O "$MODELS_DIR/tts.tar.bz2"
    mkdir -p "$MODELS_DIR/tts"
    tar -xjf "$MODELS_DIR/tts.tar.bz2" -C "$MODELS_DIR/tts"
    rm "$MODELS_DIR/tts.tar.bz2"
else
    echo "-> Piper TTS уже на месте."
fi

# ------------------------------------------------------------------------------
# Нативная библиотека ONNX Runtime для sherpa-onnx
# ------------------------------------------------------------------------------
LIB_DIR="$ROOT_DIR/deploy/lib"
mkdir -p "$LIB_DIR"

if [ ! -f "$LIB_DIR/libonnxruntime.so" ]; then
    echo "-> Скачиваем libonnxruntime.so (v1.17.1)..."
    # Ссылка на официальный релиз Microsoft ONNX Runtime
    wget -q "https://github.com/microsoft/onnxruntime/releases/download/v1.17.1/onnxruntime-linux-x64-1.17.1.tgz" -O "/tmp/ort.tgz"
    tar -xzf "/tmp/ort.tgz" -C "/tmp"
    cp "/tmp/onnxruntime-linux-x64-1.17.1/lib/libonnxruntime.so" "$LIB_DIR/libonnxruntime.so"
    rm -rf "/tmp/ort.tgz" "/tmp/onnxruntime-linux-x64-1.17.1"
else
    echo "-> libonnxruntime.so уже на месте."
fi

# ------------------------------------------------------------------------------
# Данные (расписание Excel)
# ------------------------------------------------------------------------------
echo ""
echo "=== 2. Проверка данных ==="

if [ ! -f "$DATA_DIR/schedule.xlsx" ]; then
    echo "ВНИМАНИЕ: Файл $DATA_DIR/schedule.xlsx не найден!"
    echo "Скопируйте его из раздатки хакатона (или запустите ./gradlew genSchedule)."
else
    echo "-> Расписание Excel (schedule.xlsx) на месте."
fi

echo ""
echo "=== ГОТОВО! Все модели и библиотеки на месте. ==="
echo "Не забудьте проверить .env.local перед запуском."