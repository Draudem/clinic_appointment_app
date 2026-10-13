#!/usr/bin/env bash
# Проверяет STT-модель БЕЗ запуска всего бэкенда: загружает GigaAM,
# скармливает ей тестовый wav и печатает распознанный текст.
#
# Нужен, чтобы отделить проблему «модель не работает» от проблемы
# «телефония не подключается». Если этот скрипт прошёл, а звонок не распознаётся —
# дело в AudioSocket, а не в распознавании.
#
# ЗАПУСК: ./deploy/stt-smoke-test.sh

set -euo pipefail

BACKEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL_DIR="${STT_MODEL_DIR:-$BACKEND_DIR/models/stt/sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19}"
WAV="${1:-$MODEL_DIR/test_wavs/example.wav}"

[ -f "$MODEL_DIR/model.int8.onnx" ] || {
    echo "Модель не найдена. Выполните ./deploy/fetch-stt-model.sh" >&2; exit 1; }
[ -f "$WAV" ] || { echo "WAV не найден: $WAV" >&2; exit 1; }

# -quit вместо `| head -1`: при pipefail head закрывает канал, find ловит SIGPIPE,
# и весь скрипт падает на ровном месте.
CP=$(find "$HOME/.gradle" "$HOME/.m2" -name "sherpa-onnx-java-api-*.jar" -print -quit 2>/dev/null || true)
[ -n "$CP" ] || { echo "Нет jar sherpa-onnx — выполните ./gradlew build" >&2; exit 1; }

# javac не всегда есть в PATH (минимальные образы, только JRE)
JAVA_HOME_GUESS="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
JAVAC="${JAVAC:-$JAVA_HOME_GUESS/bin/javac}"
if [ ! -x "$JAVAC" ]; then
    JAVAC=$(find "$HOME/.jdks" /usr/lib/jvm -name javac -type f -print -quit 2>/dev/null || true)
fi
if [ -z "$JAVAC" ] || [ ! -x "$JAVAC" ]; then
    echo "javac не найден — нужен JDK (не JRE). Укажите путь через переменную JAVAC." >&2
    exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/Smoke.java" <<'EOF'
import com.k2fsa.sherpa.onnx.*;
import java.nio.file.*;

public class Smoke {
  public static void main(String[] a) throws Exception {
    Path dir = Paths.get(a[0]);
    OfflineRecognizer rec = new OfflineRecognizer(
      OfflineRecognizerConfig.builder()
        .setFeatureConfig(FeatureConfig.builder().setSampleRate(16000).setFeatureDim(80).build())
        .setOfflineModelConfig(OfflineModelConfig.builder()
            .setNemo(OfflineNemoEncDecCtcModelConfig.builder()
                .setModel(dir.resolve("model.int8.onnx").toString()).build())
            .setTokens(dir.resolve("tokens.txt").toString())
            .setNumThreads(2).setProvider("cpu").build())
        .build());

    byte[] raw = Files.readAllBytes(Paths.get(a[1]));
    int off = 44;                       // стандартный заголовок WAV
    int n = (raw.length - off) / 2;
    float[] s = new float[n];
    for (int i = 0; i < n; i++) {
      int lo = raw[off + i * 2] & 0xFF, hi = raw[off + i * 2 + 1];
      s[i] = ((hi << 8) | lo) / 32768.0f;      // little-endian int16 -> float
    }
    long t0 = System.currentTimeMillis();
    OfflineStream st = rec.createStream();
    // ВАЖНО: второй аргумент — частота дискретизации, а не число отсчётов.
    // С samples.length модель молча вернёт пустую строку.
    st.acceptWaveform(s, 16000);
    rec.decode(st);
    System.out.println("ТЕКСТ: [" + rec.getResult(st).getText() + "]");
    System.out.println("аудио: " + (n / 16000.0) + " с, время: " + (System.currentTimeMillis() - t0) + " мс");
    st.release();
  }
}
EOF

javac -cp "$CP" -d "$WORK" "$WORK/Smoke.java" 2>/dev/null || "$JAVAC" -cp "$CP" -d "$WORK" "$WORK/Smoke.java"
echo "Запускаем GigaAM..."
LD_LIBRARY_PATH="$BACKEND_DIR/lib:${LD_LIBRARY_PATH:-}" java -cp "$CP:$WORK" Smoke "$MODEL_DIR" "$WAV"