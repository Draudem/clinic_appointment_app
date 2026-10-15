#!/usr/bin/env bash
#
# Сборка ONNX-модели NLU из исходников коллеги (ai_model/clinic_lm).
#
# ЗАЧЕМ. Коллега отдаёт веса в PyTorch (models/model.pt) и словарь
# (models/tokenizer_v2.json). Бэкенд на Java умеет только ONNX, поэтому модель
# приходится пересобирать. Скрипт делает это воспроизводимо, в Docker, чтобы
# на машине не нужен был Python с torch.
#
# ВАЖНО, почему всё идёт через копию. Экспорт и его самопроверка читают
# токенизатор. В исходниках коллеги export_onnx.py жёстко ссылается на
# tokenizer.json — это СТАРЫЙ словарь, общий с теггером, его номера токенов
# переставлены относительно новой модели. С ним самопроверка расшифрует вывод
# неправильно и сообщит об ошибке на пустом месте. Поэтому репозиторий коллеги
# не трогаем: работаем с копией во временном каталоге.
#
# ЧТО ПОЛУЧАЕТСЯ (и что обязано меняться вместе):
#   models/nlu/model.onnx        — веса в формате ONNX
#   models/nlu/tokenizer_v2.json — словарь, СВЯЗАННЫЙ с этими весами
#
# Использование:
#   ./deploy/export-nlu-model.sh [путь к репозиторию clinic_lm]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="${1:-$(cd "$ROOT/.." && pwd)/ai_model/clinic_lm}"
OUT="$ROOT/models/nlu"
PY_IMAGE="${PY_IMAGE:-python:3.11-slim}"

[ -d "$SRC" ] || { echo "Не найден репозиторий модели: $SRC" >&2; exit 1; }
[ -f "$SRC/models/model.pt" ] || { echo "Нет $SRC/models/model.pt" >&2; exit 1; }
[ -f "$SRC/models/tokenizer_v2.json" ] || { echo "Нет $SRC/models/tokenizer_v2.json" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> Копируем исходники модели во временный каталог (репозиторий коллеги не трогаем)"
mkdir -p "$WORK/src"
tar -c -C "$SRC" --exclude=.git --exclude=tagger --exclude=telephone . | tar -x -C "$WORK/src"

echo "==> Переключаем экспорт на tokenizer_v2.json"
python3 - "$WORK/src/train/export_onnx.py" <<'PY'
import sys
path = sys.argv[1]
s = open(path, encoding="utf-8").read()
old = 'tok = Tokenizer.from_file(str(ROOT / "models" / "tokenizer.json"))'
new = 'tok = Tokenizer.from_file(str(ROOT / "models" / "tokenizer_v2.json"))'
if old in s:
    s = s.replace(old, new, 1)
elif new in s:
    pass                      # уже переключено
else:
    sys.exit("не найдена строка с путём к токенизатору — скрипт экспорта изменился")
open(path, "w", encoding="utf-8").write(s)
print("    готово")
PY

echo "==> Ставим зависимости и экспортируем (это занимает несколько минут)"
docker run --rm -v "$WORK/src:/work" -w /work "$PY_IMAGE" bash -c '
  set -e
  pip install --no-cache-dir -q torch --index-url https://download.pytorch.org/whl/cpu
  pip install --no-cache-dir -q tokenizers numpy onnxruntime onnxscript
  python train/export_onnx.py
'

echo "==> Кладём результат в $OUT"
mkdir -p "$OUT"
cp "$WORK/src/models/model.onnx" "$OUT/model.onnx"
cp "$SRC/models/tokenizer_v2.json" "$OUT/tokenizer_v2.json"

echo
echo "Готово:"
ls -la "$OUT"
echo
echo "ВАЖНО: model.onnx и tokenizer_v2.json — связанная пара."
echo "Менять их можно только вместе, иначе NLU будет молча разъезжаться."