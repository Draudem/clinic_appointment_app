#!/usr/bin/env bash
# Запуск/передеплой бэкенда клиники.
# Запускать НА СЕРВЕРЕ из каталога /opt/clinic (там Dockerfile, docker-compose.yml, .env, JAR).
# Скрипт идемпотентный: повторный запуск пересоберёт образ и перезапустит контейнеры.
#
# Если docker compose ругается на ключ "name:" (старая версия) — убери его из
# docker-compose.yml или замени все "docker compose" ниже на "docker compose -p clinic".
set -euo pipefail

cd "$(dirname "$0")"
PORT=8090
STT_PORT=8091
URL="http://$(curl -s -m 3 ifconfig.me 2>/dev/null || echo localhost):${PORT}"

echo "== 1/5 Проверка файлов в $(pwd) =="
for f in Dockerfile docker-compose.yml .env clinic-backend-0.1.0.jar model.onnx tokenizer.json; do
  if [ ! -f "$f" ]; then
    echo "ОШИБКА: нет файла $f (скопируй его с локальной машины через scp)"
    exit 1
  fi
done

# Файлы STT проверяем ОТДЕЛЬНО и с понятным объяснением.
# Без них бэкенд стартует, тихо пишет предупреждение в лог и НЕ ПОДНИМАЕТ порт 8091:
# напарник увидит «соединение отклонено» и начнёт искать проблему у себя, а не у нас.
for f in model.int8.onnx tokens.txt libonnxruntime.so; do
  if [ ! -f "$f" ]; then
    echo "ОШИБКА: нет файла $f — без него не поднимется порт AudioSocket (${STT_PORT})."
    if [ "$f" = "libonnxruntime.so" ]; then
      echo "        Скачать: на локальной машине выполни deploy/fetch-stt-libs.sh"
    else
      echo "        Скачать: на локальной машине выполни deploy/fetch-stt-model.sh"
    fi
    echo "        Затем повтори деплой: ./deploy.sh"
    exit 1
  fi
done
echo "все файлы на месте (включая STT)"

echo "== 2/5 Проверка окружения =="

# Плагин docker compose (v2). Без него 'docker compose up -d' падает с невнятным
# «unknown shorthand flag: 'd'», что выглядит как баг скрипта, а не как отсутствие плагина.
if ! docker compose version >/dev/null 2>&1; then
  echo "ОШИБКА: не работает «docker compose» (нужен плагин v2)."
  echo "        Установи на сервере:"
  echo "          mkdir -p ~/.docker/cli-plugins"
  echo "          curl -sSL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 -o ~/.docker/cli-plugins/docker-compose"
  echo "          chmod +x ~/.docker/cli-plugins/docker-compose"
  echo "        Проверка: docker compose version"
  exit 1
fi
echo "docker compose доступен"

# Свободная память. Модель распознавания GigaAM живёт в нативной памяти, поэтому при
# нехватке контейнер умирает по OOM с невнятным «Exited (137)» в docker ps.
# Читаем /proc/meminfo, а не вывод free: в русской локали там «Память:», а не «Mem:»,
# из-за чего разбор free|awk молча даёт ПУСТОЕ значение.
NEED_MB="${MIN_FREE_MB:-2048}"
FREE_MB=$(awk '/^MemAvailable:/{print int($2/1024)}' /proc/meminfo 2>/dev/null || true)
if [ -n "$FREE_MB" ]; then
  if [ "$FREE_MB" -lt "$NEED_MB" ]; then
    echo "ВНИМАНИЕ: доступно ${FREE_MB} МБ, стенду нужно около ${NEED_MB} МБ"
    echo "           (бэкенд с моделью распознавания ~1200m + postgres 192m)."
    echo "           Если контейнер упадёт с Exited (137) — не хватило памяти."
  else
    echo "памяти хватает: доступно ${FREE_MB} МБ"
  fi
else
  echo "память: не удалось определить автоматически (проверь вручную: free -m)"
fi

echo
echo "== 3/5 Открываем порты в ufw =="
if command -v ufw >/dev/null 2>&1; then
  ufw allow "${PORT}/tcp" >/dev/null 2>&1 || true
  ufw allow "${STT_PORT}/tcp" >/dev/null 2>&1 || true
  echo "ufw: разрешены ${PORT}/tcp и ${STT_PORT}/tcp (AudioSocket)"
else
  echo "ufw не найден — пропускаем (порты публикуются Docker мимо UFW, но проверь файрвол провайдера)"
fi

echo "== 4/5 Собираем и поднимаем контейнеры =="
docker compose up -d --build

echo "== 5/5 Ждём готовности бэкенда =="
if ! command -v curl >/dev/null 2>&1; then
  echo "curl на хосте отсутствует — статус смотри командой: docker compose ps"
  sleep 10
  docker compose ps
  exit 0
fi

for i in $(seq 1 60); do
  if curl -fsS -m 2 "http://localhost:${PORT}/api/health" >/dev/null 2>&1; then
    echo
    echo "ГОТОВО. Health: $(curl -sS "http://localhost:${PORT}/api/health")"
    docker compose ps
    echo
    echo "Проверка состояния STT (не закрыт ли порт AudioSocket ${STT_PORT}):"
    STT_STATUS=$(curl -fsS -m 3 "http://localhost:${PORT}/api/stt/public/status" 2>/dev/null || echo "")
    if [ -z "$STT_STATUS" ]; then
      echo "  ВНИМАНИЕ: STT не отвечает. Без модели порт ${STT_PORT} не поднимется."
      echo "  Смотри в логах: docker compose logs backend | grep -i 'stt\\|модель'"
    else
      echo "  $STT_STATUS"
    fi
    echo
    echo "Проверка снаружи:  curl http://${URL#http://}"
    exit 0
  fi
  sleep 1
done

echo
echo "ОШИБКА: бэкенд не ответил за 60 секунд. Последние логи:"
docker compose logs backend --tail 80
exit 1
