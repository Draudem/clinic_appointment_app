#!/usr/bin/env bash
# Скачивает модель синтеза речи Piper (женский голос, русский) для сервера.
#
# Модель: vits-piper-ru_RU-irina-medium-int8 (~20 МБ в архиве, ~37 МБ после распаковки).
# Русский голос родной для модели, в одной модели ОДИН голос (не нужно подбирать sid),
# фонемы считает espeak-ng из комплекта — словарь-лексикон не нужен.
# Работает через тот же sherpa-onnx, что и распознавание, новых зависимостей нет.
#
# Модель НЕ лежит в репозитории (в .gitignore: models/tts/), качается автоматически
# при деплое. Вручную:
#   ./deploy/fetch-tts-model.sh
set -euo pipefail

BACKEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL_NAME="vits-piper-ru_RU-irina-medium-int8"
DEST="$BACKEND_DIR/models/tts"

if [ -f "$DEST/$MODEL_NAME/ru_RU-irina-medium.onnx" ]; then
    echo "Модель синтеза уже на месте: $DEST/$MODEL_NAME"
    exit 0
fi

mkdir -p "$DEST"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${MODEL_NAME}.tar.bz2"
echo "Скачиваем $MODEL_NAME (~20 МБ)..."
curl -sSL -o "$TMP/tts.tar.bz2" "$URL"
tar xjf "$TMP/tts.tar.bz2" -C "$DEST"

echo "Готово:"
ls -la "$DEST/$MODEL_NAME" | head -5
echo
echo "Без этой модели TTS не поднимется — звонящий услышит тишину,"
echo "но звонки по-прежнему принимаются и речь распознаётся."