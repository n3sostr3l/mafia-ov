# REST и WebSocket

Базовый префикс: `/api/v1`. JSON UTF-8. Это собственный API Studio, **не API Polemica**.

Для изменяющего запроса сначала выполните `GET /auth/csrf`, сохраните cookie сессии и передайте возвращённый `token` в заголовке с именем из `header` (`X-CSRF-TOKEN`). CSRF требуется и для регистрации/входа. Серверная сессия устанавливается при успешном входе. Cookie — HttpOnly, SameSite=Strict; Secure управляется окружением. После входа снова получите CSRF-токен.

## Авторизация

| Метод | Путь | Тело / результат |
|---|---|---|
| GET | `/auth/csrf` | `{token, header}`; без входа |
| POST | `/auth/register` | `{username,email,password}`; сообщение. Только в dev — `verificationUrl` |
| POST | `/auth/verify` | `{token}`; подтверждение email, срок 24 часа, одноразовый |
| POST | `/auth/login` | `{login,password}`; `{id,username,email}` и сессия |
| GET | `/auth/me` | `{id,username,email}` |
| POST | `/auth/logout` | Завершение сессии |

Логин: 3–40 латинских букв, цифр или `_`; регистр нормализуется. Пароль: от 10 символов, не более 72 байт UTF-8 при регистрации. Вход и регистрация ограничены 20 попытками в минуту с одного адреса, nginx/backend за одним прокси могут видеть общий адрес; для масштабирования нужен доверенный прокси и распределённый лимитер. Неверный вход — 401, неподтверждённая почта — 403, лимит — 429.

## Турниры и партии

Все следующие закрытые операции ограничены владельцем.

| Метод | Путь | Тело / результат |
|---|---|---|
| GET | `/tournaments?query=&source=LOCAL` | Поиск своих турниров: `{items,total}` |
| GET | `/tournaments?query=&source=POLEMICA` | 503, пока доступ к API не подтверждён |
| POST | `/tournaments` | `{name,externalId?}`; локальный турнир |
| GET | `/tournaments/{id}` | `{tournament,games}`; партии отсортированы по столу и номеру |
| POST | `/tournaments/{id}/games` | `{tableNumber,gameNumber,demo}`; создаёт 10 игроков |
| GET | `/games/{id}` | Закрытый снимок `GameView` |
| PUT | `/games/{id}` | `{revision,state,description?}`; обновлённый снимок |
| GET | `/games/{id}/events` | Последние 50 `{id,revision,description,createdAt}` |
| POST | `/games/{id}/restore/{eventId}` | `{revision}`; восстановление выбранной версии |
| GET | `/games/{id}/overlay-link` | `{token}` только владельцу |
| POST | `/games/{id}/overlay-link` | Новый `{token}`, старый отозван |
| POST | `/uploads` | multipart поле `file`; `{id}` |
| GET | `/uploads/{id}` | PNG, доступ только владельцу |

`GameView`: `{id,tournamentId,tournamentName,tableNumber,gameNumber,revision,updatedAt,state}`. `revision` — ожидаемая версия, а не счётчик, назначаемый клиентом. Несовпадение даёт 409. Номер стола 1–100, партия 1–10000, раунд 1–100. Места ровно 1–10 без повторов. Состав ролей допускает промежуточное редактирование, поэтому правило «6 мирных + 2 мафии + Дон + Шериф» автоматически не навязывается.

### Поля state

- `players`: десять объектов `{seat,name,role,status,pu,revealRole,photo,importedPhoto,testament}`. `photo` и `importedPhoto` — UUID загрузки, не внешняя ссылка. `importedPhoto` зарезервировано для адаптера.
- `role`: `CIVILIAN | MAFIA | DON | SHERIFF`; `UNKNOWN` используется только в публичной проекции.
- `status`: `ALIVE | SHOT | VOTED`. ПУ — независимый флаг.
- `speaker`: номер или `null`. Выбывшему игроку нельзя передать слово; UI автоматически снимает выступление при выбывании.
- `phase`: `PREPARATION | DAY | VOTING | NIGHT | FINISHED`.
- `round`: номер раунда; `nominees`: уникальные номера в хронологии выставления.
- `testament`: `null` либо `{mode:'COLORS',colors:[{seat,color}],wheel:null}` (до трёх разных мест; цвет `CIVILIAN`/`MAFIA`) либо `{mode:'WHEEL',wheel:1,colors:[]}`.
- `checks`: `{id,by,seat,result,round,published}`. `by=DON`: `SHERIFF | NOT_SHERIFF`; `by=SHERIFF`: `RED | BLACK`. Массив сохраняет хронологию добавления.
- `showRoles`, `showChecks`: общие разрешения публичного показа.
- `theme`: `{civilian,mafia,special,transparent,width,height,useRolePhotos,rolePhotos}`. Цвета `#RRGGBB`, ширина 640–3840, высота 360–2160. `rolePhotos` — отображение роли в UUID загрузки.

## Публичные маршруты

| Метод | Путь | Назначение |
|---|---|---|
| GET | `/public/games/{id}?token=…` | Только публичный снимок, Cache-Control: no-store |
| GET | `/public/games/{id}/images/{imageId}?token=…` | Только фото, используемое текущей публичной проекцией |
| GET | `/integration/status` | Статус `UNVERIFIED`, список подтверждённых полей пуст |
| GET | `/health` | `{status:'ok'}`; готовность HTTP, не полный мониторинг БД |

В публичном ответе скрытые роли заменены на `UNKNOWN`, неопубликованные проверки отсутствуют, ролевые фото и секреты не передаются. Неверный/отозванный токен — 404. Идентификатор партии без токена не даёт доступ.

## WebSocket

- Судья: `/ws/games/{id}` с cookie-сессией владельца.
- OBS: `/ws/games/{id}?token=…` без приватной cookie.
- Первое сообщение — полный текущий `GameView`; после сохранений — новый полный снимок. Публичный сокет всегда получает публичную проекцию.
- Клиент отправляет текст `ping` раз в 10 секунд и получает актуальный снимок. Сокет не принимает операции редактирования.
- Повторное подключение через 5 секунд; параллельная HTTP-сверка каждые 10 секунд. Это локальный механизм, не опрос Polemica.
- После отзыва токена старые публичные сокеты закрываются. После выхода владельца приватный сокет закрывается при следующей отправке.

Ошибки API имеют безопасное сообщение `{message}`. 400 — валидация, 401 — нужна сессия, 403 — CSRF/права/подтверждение почты, 404 — отсутствует или закрыто, 409 — конфликт, 413 — превышен размер загрузки, 429 — лимит, 503 — SMTP или неподключённый внешний API.
