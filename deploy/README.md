# Деплой на общий сервер (Docker)

Сервер: Ubuntu 26.04, `159.194.246.91`, доступ root по SSH. На сервере уже крутится
чужой стек `therapy-*` — **его не трогаем**. У нас собственный изолированный стек
`clinic` (свой network, свой volume, свой postgres), порт **8090**.

Память на сервере критически мало (956 MiB), поэтому в compose заданы
`mem_limit` (db 256m, backend 512m) и `-Xmx256m` для JVM. Дополнительно на сервере
настроен swap 1 ГБ как страховка от OOM-killer (лимиты при этом сохраняются).

## Быстрый деплой одной командой

```bash
# Из КОРНЯ бэкенда: собрать из исходников, скопировать и запустить на сервере
cd VoiceAssistant/backend
./deploy.sh

# Остановить и полностью очистить наше на сервере
./deploy.sh down
```

`deploy.sh` — точка входа из корня, вся логика в `deploy/deploy-local.sh`
(сборка JAR -> scp -> вызов серверного `deploy/up.sh`). Повторный запуск = передеплой
новой версии. Другой адрес: `SERVER=root@1.2.3.4 ./deploy.sh`.

### Про пароль SSH

Сервер пускает по паролю, поэтому `deploy.sh` использует SSH-мультиплексирование:
пароль спрашивается **один раз за запуск**, а `scp` и последующие `ssh` переиспользуют
уже открытое соединение. Пароль нигде не сохраняется.

Чтобы не вводить пароль вообще — один раз установи SSH-ключ:
```bash
ssh-copy-id root@159.194.246.91     # спросит пароль последний раз
```
После этого деплой проходит без ввода пароля (проверка: `ssh root@159.194.246.91`).

## Страница эндпоинтов (Swagger UI) в браузере

Да, теперь есть — бэкенд отдаёт интерактивную страницу со всеми эндпоинтами:

- локально: <http://localhost:8080/swagger-ui/index.html>
- на сервере: <http://159.194.246.91:8090/swagger-ui/index.html>
- сам OpenAPI-документ (JSON): `/v3/api-docs`

Из Swagger UI можно вызывать эндпоинты прямо в браузере: `POST /api/auth/login`,
затем «Authorize» -> вставить `Bearer <токен>` -> проверять `/api/auth/me` и
`/api/appointments`. Реализовано через `springdoc-openapi-starter-webmvc-ui`
(см. `build.gradle.kts`), пути `/swagger-ui/**` и `/v3/api-docs/**` открыты в
`SecurityConfig`.

Ручные шаги ниже — на случай, если скрипты недоступны.

## Остановка и очистка (удаление наших файлов с сервера)

```bash
# С локальной машины: остановить стек и удалить всё наше с сервера
./deploy/deploy-local.sh down
```

Тот же результат вручную:
```bash
scp deploy/down.sh root@159.194.246.91:/opt/clinic/
ssh root@159.194.246.91 "chmod +x /opt/clinic/down.sh && /opt/clinic/down.sh"
```

`down.sh` удаляет **только наше**: контейнеры `clinic-*`, сеть, volume `clinic-db-data`
и локально собранный образ `clinic-backend`, закрывает порт в ufw и удаляет `/opt/clinic`.
Чужие контейнеры/образы/тома (в т.ч. `therapy-*`, скачанные `postgres:16`,
`eclipse-temurin`) **не трогаются**: `--rmi local` снимает лишь наш образ, глобальные
`docker ... prune` не используются. После очистки для следующего запуска нужно снова
выполнить `./deploy/deploy-local.sh` (файлы заново скопируются).

> Выполняй очистку, пока VPS включён — выключенному серверу команду не отправить.

## 1. Локально: собрать JAR

```bash
cd VoiceAssistant/backend
./gradlew bootJar
```

## 2. Отправить на сервер

```bash
ssh root@159.194.246.91 "mkdir -p /opt/clinic"

scp build/libs/clinic-backend-0.1.0.jar \
    deploy/Dockerfile deploy/docker-compose.yml deploy/.env \
    root@159.194.246.91:/opt/clinic/
```

## 3. На сервере: поднять

```bash
ssh root@159.194.246.91
cd /opt/clinic
docker compose up -d --build
docker compose ps                      # db: healthy, backend: running
docker compose logs backend --tail 50  # ждём "Started BackendApplication"
ufw allow 8090/tcp
docker stats --no-stream               # память нашего стека должна быть < 500M
```

## 4. Проверка (с любой машины извне)

```bash
curl http://159.194.246.91:8090/api/health
# {"status":"UP"}

curl -s -X POST http://159.194.246.91:8090/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"login":"admin","password":"admin123"}'
# {"token":"...","login":"admin","role":"ADMIN"}

curl http://159.194.246.91:8090/api/appointments -H "Authorization: Bearer <TOKEN>"
# {"appointments":[...]}   без токена — 401
```

Если снаружи не отвечает, а на сервере контейнер running — порт режет файрвол
провайдера (Docker публикует порты мимо UFW), искать в панели хостинга.

## 5. Мобильное приложение

- Базовый URL: `http://159.194.246.91:8090`
- Android 9+ блокирует HTTP: в манифесте нужен `android:usesCleartextTraffic="true"`
- Демо-доступ: `admin` / `admin123` (создаётся автоматически в чистой БД)

## Полезное

```bash
docker compose logs -f backend    # логи
docker compose restart backend    # перезапуск
docker compose stop backend       # выключить бэк (БД остаётся работать)
docker compose down               # остановить весь стек (volume с БД сохраняется)
docker compose down -v            # остановить и УДАЛИТЬ БД (осторожно)
docker exec -it clinic-db psql -U clinic -d clinic   # консоль БД
```

Подключение из DBeaver/psql прямо на сервере: host `localhost`, port `5433`,
БД `clinic`, юзер `clinic`, пароль из `.env`. Наружу БД не проброшена —
на другие машины доступ только через ssh-туннель:
`ssh -L 5433:localhost:5433 root@159.194.246.91`, затем DBeaver → localhost:5433.

## Подключение backend2 (когда будет готов)

Добавить в `docker-compose.yml` сервис `backend2`: свой `Dockerfile.backend2`
(`dockerfile: Dockerfile.backend2`), порт `8091:8091`, те же env — и переключаться:

```bash
docker compose stop backend
docker compose up -d --build backend2
```

БД одна на оба бэка (`clinic`), данные пользователей сохраняются.
