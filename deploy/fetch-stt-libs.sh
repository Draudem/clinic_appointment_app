#!/usr/bin/env bash
# Скачивает нативные библиотеки, нужные серверному STT (GigaAM через sherpa-onnx).
#
# ЗАЧЕМ ЭТОТ СКРИПТ
# -----------------
# Java-обёртка sherpa-onnx кладёт внутрь себя только libsherpa-onnx-jni.so, но НЕ кладёт
# libonnxruntime.so, на которую она линкуется. Без неё при загрузке JNI падает:
#     UnsatisfiedLinkError: libonnxruntime.so: невозможно открыть разделяемый объектный файл
#
# ПОЧЕМУ ИМЕННО 1.17.1, А НЕ ТА, ЧТО У НАС В ПРОЕКТЕ
# ---------------------------------------------------
# В build.gradle.kts стоит com.microsoft.onnxruntime:1.22.0 (для NLU-модели).
# Её .so лежит под SONAME libonnxruntime.so.1 и помечена VERS_1.22.0.
# А libsherpa-onnx-jni.so собрана против VERS_1.17.1 и ждёт SONAME без суффикса:
#     version `VERS_1.17.1' not found (required by libsherpa-onnx-jni.so)
# Это РАЗНЫЕ SONAME, поэтому обе библиотеки спокойно живут в одном процессе:
#     NLU -> libonnxruntime.so.1   (1.22.0)
#     STT -> libonnxruntime.so     (1.17.1)
# Подменять версию NLU на 1.17.1 нельзя — модель коллеги собрана под 1.22.
#
# ЗАПУСК: ./deploy/fetch-stt-libs.sh   (затем в LD_LIBRARY_PATH добавить ./lib)

set -euo pipefail

BACKEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIB_DIR="$BACKEND_DIR/lib"
VERSION="1.17.1"

mkdir -p "$LIB_DIR"

if [ -f "$LIB_DIR/libonnxruntime.so" ]; then
    echo "lib/libonnxruntime.so уже на месте — пропускаем скачивание."
    exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

URL="https://github.com/microsoft/onnxruntime/releases/download/v${VERSION}/onnxruntime-linux-x64-${VERSION}.tgz"
echo "Скачиваем onnxruntime ${VERSION} (нужна для sherpa-onnx)..."
curl -sSL -o "$TMP/ort.tgz" "$URL"
tar xzf "$TMP/ort.tgz" -C "$TMP"
cp "$TMP"/onnxruntime-linux-x64-${VERSION}/lib/libonnxruntime.so* "$LIB_DIR/"

echo "Готово:"
ls -la "$LIB_DIR"
echo
echo "Теперь запускайте бэкенд с:"
echo "  export LD_LIBRARY_PATH=$LIB_DIR:\${LD_LIBRARY_PATH:-}"