#!/usr/bin/env bash
# Скачивает STT-модель GigaAM (русский) для серверного распознавания речи.
#
# Модель та же, что используется в мобильном приложении
# (SherpaSttEngine -> sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19),
# поэтому речь, которая распознаётся на устройстве, распознаётся и на сервере.
#
# Размер ~160 МБ в архиве, ~236 МБ после распаковки. В git не попадает.
# Нативная библиотека ставится отдельно: ./fetch-stt-libs.sh

set -euo pipefail

BACKEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL_NAME="sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19"
DEST="$BACKEND_DIR/models/stt"

if [ -f "$DEST/$MODEL_NAME/model.int8.onnx" ]; then
    echo "Модель уже на месте: $DEST/$MODEL_NAME"
    exit 0
fi

mkdir -p "$DEST"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${MODEL_NAME}.tar.bz2"
echo "Скачиваем $MODEL_NAME (~160 МБ)..."
curl -sSL -o "$TMP/model.tar.bz2" "$URL"
tar xjf "$TMP/model.tar.bz2" -C "$DEST"

echo "Готово:"
ls -la "$DEST/$MODEL_NAME"
echo
echo "Проверить распознавание изолированно можно так:"
echo "  ./deploy/stt-smoke-test.sh"