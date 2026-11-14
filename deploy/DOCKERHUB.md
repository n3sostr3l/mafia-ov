# Развёртывание готовых образов

Dockerfile и `.dockerignore` находятся в `backend/` и `frontend/`. На сервере не нужны Maven, Node.js, Git и исходники: только Docker Engine с Compose, файл `compose.images.yml` и `.env`.

## На машине сборки

Войдите в свой Docker Hub, подставьте свой namespace и одинаковый тег в обе команды. Для маленького сервера сборка выполняется на рабочем компьютере или CI.

```sh
docker login
docker buildx build --platform linux/amd64 -t YOUR_NAMESPACE/polemica-backend:2026-11-14 --push ./backend
docker buildx build --platform linux/amd64 -t YOUR_NAMESPACE/polemica-web:2026-11-14 --push ./frontend
```

Для ARM-сервера используйте `linux/arm64` либо соберите обе архитектуры через `--platform linux/amd64,linux/arm64`.

## На сервере

Скопируйте `deploy/compose.images.yml` как `compose.yml`, а `deploy/images.env.example` как `.env`. Укажите `DOCKERHUB_NAMESPACE`, тег `IMAGE_TAG=2026-11-14`, свой `DATABASE_PASSWORD` и внешний `PUBLIC_URL`.

```sh
docker compose --env-file .env -f compose.yml pull
docker compose --env-file .env -f compose.yml up -d
docker compose --env-file .env -f compose.yml ps
```

Открыть `http://SERVER:8088`. В турнире нажмите «Задать пароль», затем передайте общий пароль судьям. Оверлей открывается по OBS-ссылке без пароля. Для домена с HTTPS используйте [пример Caddy](Caddyfile.example), `PUBLIC_URL=https://your-domain` и `BIND_ADDRESS=127.0.0.1`.

Лимиты контейнеров: PostgreSQL 256 МБ, Java 512 МБ, Nginx 64 МБ. Это суммарные лимиты контейнеров, а не обещание работы всего сервера в 832 МБ: ОС и Docker также требуют память. Порты БД и Java наружу не публикуются. Данные сохраняются в `postgres_data` и `uploads`; при переносе существующей установки используйте прежнее Compose project name (`-p`) для подключения прежних volumes.

Обновление: поменяйте общий тег, выполните `pull` и `up -d`. Перед обновлением сохраните дамп PostgreSQL и volume `uploads`. Архив турнира подходит для выборочного переноса, но не заменяет полную резервную копию БД.

Команды публикации не выполняются автоматически: для них требуется ваш Docker Hub namespace и вход в registry.
