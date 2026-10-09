#!/usr/bin/env bash
# Остановка и ПОЛНАЯ очистка только нашего стека клиники.
# Запускать НА СЕРВЕРЕ:  /opt/clinic/down.sh
#
# ГАРАНТИИ БЕЗОПАСНОСТИ ДЛЯ ЧУЖИХ ПРОЕКТОВ:
#  - работаем строго в рамках compose-проекта clinic (контейнеры clinic-*, сеть, volume);
#  - "down --rmi local" удаляет ТОЛЬКО локально собранный образ clinic-backend,
#    но НЕ скачанные посторонние/базовые образы (postgres:16, eclipse-temurin и т.п.);
#  - никаких глобальных "docker system prune", "docker volume prune", "docker image prune -a".
set -euo pipefail

REMOTE_DIR="${REMOTE_DIR:-/opt/clinic}"
PORT=8090

if [ ! -d "$REMOTE_DIR" ]; then
  echo "Каталог $REMOTE_DIR уже отсутствует — чистить нечего."
  exit 0
fi
cd "$REMOTE_DIR"

echo "== 1/4 Останавливаем и удаляем наш стек (образ, volume, сеть) =="
docker compose down -v --rmi local

# Страховка: образы, помеченные ярлыком НАШЕГО compose-проекта (чужие ярлыки не трогаем)
img_ids=$(docker images --filter 'label=com.docker.compose.project=clinic' -q)
if [ -n "$img_ids" ]; then
  # shellcheck disable=SC2086
  docker rmi $img_ids 2>/dev/null || true
fi

echo "== 2/4 Закрываем порт в ufw =="
if command -v ufw >/dev/null 2>&1; then
  ufw delete allow "${PORT}/tcp" 2>/dev/null || true
  echo "ufw: правило ${PORT}/tcp снято (если было)"
else
  echo "ufw не найден — пропускаем"
fi

echo "== 3/4 Проверяем, что наши следы не остались (чужое не трогаем) =="
leftover=0
docker ps -a --filter 'name=clinic-' --format '{{.Names}}' | grep -q . && { echo "! остались контейнеры clinic-*"; leftover=1; }
docker volume ls --filter 'name=clinic' --format '{{.Name}}' | grep -q . && { echo "! остались тома clinic*"; leftover=1; }
docker network ls --filter 'name=clinic' --format '{{.Name}}' | grep -q . && { echo "! осталась сеть clinic*"; leftover=1; }
docker images --format '{{.Repository}}:{{.Tag}}' | grep -q '^clinic' && { echo "! остался образ clinic*"; leftover=1; }
[ "$leftover" -eq 0 ] && echo "наших контейнеров/томов/сетей/образов не осталось"

echo "== 4/4 Удаляем файлы проекта =="
cd /
rm -rf "$REMOTE_DIR"
echo "Каталог $REMOTE_DIR удалён. Очистка завершена."
