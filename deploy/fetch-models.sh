#!/usr/bin/env bash
set -euo pipefail

# Скрипт автоматического скачивания всех моделей (STT, TTS, NLU) и проверки данных.
# Модели большие, поэтому они не лежат в Git.
# Запуск из корня проекта: ./deploy/fetch-models.sh
#
# Проверка целостности: после скачивания каждый файл сверяется по SHA-256
# с манифестом deploy/checksums.txt (формат: <hash>  <путь-от-корня>).
# Если манифеста нет или хеш для файла не задан — предупреждение, но не падение.
# Если хеш задан и НЕ совпал — файл удаляется, скрипт завершается с кодом 1.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODELS_DIR="$ROOT_DIR/models"
DATA_DIR="$ROOT_DIR/data"
CHECKSUMS_FILE="$ROOT_DIR/deploy/checksums.txt"

mkdir -p "$MODELS_DIR" "$DATA_DIR"

# ------------------------------------------------------------------------------
# Проверка контрольных сумм
# ------------------------------------------------------------------------------
expected_hash() {
    local rel="$1"
    [ -f "$CHECKSUMS_FILE" ] || { echo ""; return; }
    awk -v p="$rel" '$2 == p {print $1; exit}' "$CHECKSUMS_FILE"
}

verify_sha256() {
    local file="$1"
    local rel="$2"
    local expected
    expected="$(expected_hash "$rel")"
    if [ -z "$expected" ]; then
        echo "   ВНИМАНИЕ: хеш для $rel не задан в deploy/checksums.txt — пропускаем проверку"
        return 0
    fi
    local actual
    actual="$(sha256sum "$file" | awk '{print $1}')"
    if [ "$actual" != "$expected" ]; then
        echo "   ОШИБКА: хеш не совпадает для $rel"
        echo "     ожидалось: $expected"
        echo "     получено:  $actual"
        rm -f "$file"
        return 1
    fi
    echo "   OK: хеш $rel совпадает"
}

# ------------------------------------------------------------------------------
# Вспомогательное: скачать (если нет) + проверить хеш
# ------------------------------------------------------------------------------
fetch_file() {
    local url="$1"
    local dest="$2"
    local rel="$3"
    if [ -f "$dest" ]; then
        echo "-> $(basename "$dest") уже на месте, проверяем хеш."
        verify_sha256 "$dest" "$rel" || exit 1
        return 0
    fi
    echo "-> Скачиваем $(basename "$dest")..."
    wget -q --show-progress "$url" -O "$dest"
    verify_sha256 "$dest" "$rel" || {
        echo "Скачивание $rel не удалось (битый файл или подмена). Выход."
        exit 1
    }
}

fetch_archive() {
    local url="$1"
    local dir="$2"
    local archive_rel="$3"
    local tmp="$MODELS_DIR/$(basename "$archive_rel")"

    if [ -d "$dir" ]; then
        echo "-> $(basename "$dir") уже распакован."
        return 0
    fi
    echo "-> Скачиваем $(basename "$archive_rel")..."
    wget -q --show-progress "$url" -O "$tmp"
    verify_sha256 "$tmp" "$archive_rel" || {
        echo "Архив $archive_rel повреждён. Выход."
        rm -f "$tmp"
        exit 1
    }
    mkdir -p "$(dirname "$dir")"
    tar -xjf "$tmp" -C "$(dirname "$dir")"
    rm -f "$tmp"
}

echo "=== 1. Проверка и скачивание моделей в $MODELS_DIR ==="

# ------------------------------------------------------------------------------
# NLU Модель (ONNX) + Токенизатор
# ------------------------------------------------------------------------------
NLU_DIR="$MODELS_DIR/nlu"
mkdir -p "$NLU_DIR"

fetch_file \
    "https://github.com/haitaks/clinic_lm/releases/download/v3/model.onnx" \
    "$NLU_DIR/model.onnx" \
    "models/nlu/model.onnx"

fetch_file \
    "https://github.com/haitaks/clinic_lm/releases/download/v3/tokenizer_v2.json" \
    "$NLU_DIR/tokenizer_v2.json" \
    "models/nlu/tokenizer_v2.json"

# ------------------------------------------------------------------------------
# GigaAM STT (Распознавание речи)
# ------------------------------------------------------------------------------
STT_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19.tar.bz2"
STT_DIR="$MODELS_DIR/stt/sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19"

fetch_archive "$STT_URL" "$STT_DIR" "models/stt.tar.bz2"

# ------------------------------------------------------------------------------
# Piper TTS (Синтез речи)
# ------------------------------------------------------------------------------
TTS_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-irina-medium-int8.tar.bz2"
TTS_DIR="$MODELS_DIR/tts/vits-piper-ru_RU-irina-medium-int8"

fetch_archive "$TTS_URL" "$TTS_DIR" "models/tts.tar.bz2"

# ------------------------------------------------------------------------------
# Нативная библиотека ONNX Runtime для sherpa-onnx
# ------------------------------------------------------------------------------
LIB_DIR="$ROOT_DIR/deploy/lib"
mkdir -p "$LIB_DIR"
ORT_LIB="$LIB_DIR/libonnxruntime.so"
ORT_TMP="/tmp/onnxruntime-linux-x64-1.17.1"

if [ -f "$ORT_LIB" ]; then
    echo "-> libonnxruntime.so уже на месте, проверяем хеш."
    verify_sha256 "$ORT_LIB" "deploy/lib/libonnxruntime.so" || exit 1
else
    echo "-> Скачиваем libonnxruntime.so (v1.17.1)..."
    wget -q "https://github.com/microsoft/onnxruntime/releases/download/v1.17.1/onnxruntime-linux-x64-1.17.1.tgz" -O "/tmp/ort.tgz"
    tar -xzf "/tmp/ort.tgz" -C "/tmp"
    cp "$ORT_TMP/lib/libonnxruntime.so" "$ORT_LIB"
    rm -rf "/tmp/ort.tgz" "$ORT_TMP"
    verify_sha256 "$ORT_LIB" "deploy/lib/libonnxruntime.so" || exit 1
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