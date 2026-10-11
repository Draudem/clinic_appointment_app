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
URL="http://$(curl -s -m 3 ifconfig.me 2>/dev/null || echo localhost):${PORT}"

echo "== 1/4 Проверка файлов в $(pwd) =="
for f in Dockerfile docker-compose.yml .env clinic-backend-0.1.0.jar model.onnx tokenizer.json; do
  if [ ! -f "$f" ]; then
    echo "ОШИБКА: нет файла $f (скопируй его с локальной машины через scp)"
    exit 1
  fi
done
echo "все файлы на месте"

echo "== 2/4 Открываем порт в ufw =="
if command -v ufw >/dev/null 2>&1; then
  ufw allow "${PORT}/tcp" >/dev/null 2>&1 || true
  echo "ufw: разрешён ${PORT}/tcp"
else
  echo "ufw не найден — пропускаем (порт у Docker публикуется мимо UFW, но проверь файрвол провайдера)"
fi

echo "== 3/4 Собираем и поднимаем контейнеры =="
docker compose up -d --build

echo "== 4/4 Ждём готовности бэкенда =="
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
    echo "Проверка снаружи:  curl http://${URL#http://}"
    exit 0
  fi
  sleep 1
done

echo
echo "ОШИБКА: бэкенд не ответил за 60 секунд. Последние логи:"
docker compose logs backend --tail 80
exit 1
