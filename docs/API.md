# REST и WebSocket

Базовый префикс: `/api/v1`. JSON UTF-8. Это собственный API Studio, **не API Polemica**.

API управления открыт: вход, регистрация, подтверждение почты, cookie-сессии и CSRF не используются. Все турниры, партии, история и загрузки общие. CORS разрешает любой origin без credentials. Старые маршруты `/auth/*` удалены и возвращают 404.

## Турниры и партии

Все следующие операции доступны без авторизации.

| Метод | Путь | Тело / результат |
|---|---|---|
| GET | `/tournaments?query=&source=LOCAL` | Поиск общих турниров: `{items,total}` |
| GET | `/tournaments?query=&source=POLEMICA` | 503, пока доступ к API не подтверждён |
| POST | `/tournaments` | `{name,externalId?}`; локальный турнир |
| GET | `/tournaments/{id}` | `{tournament,games}`; партии отсортированы по столу и номеру |
| POST | `/tournaments/{id}/games` | `{tableNumber,gameNumber,demo}`; создаёт 10 игроков |
| GET | `/games/{id}` | Полный снимок `GameView` |
| PUT | `/games/{id}` | `{revision,state,description?}`; обновлённый снимок |
| GET | `/games/{id}/events` | Последние 50 `{id,revision,description,createdAt}` |
| POST | `/games/{id}/restore/{eventId}` | `{revision}`; восстановление выбранной версии |
| GET | `/games/{id}/overlay-link` | `{token}` для OBS |
| POST | `/games/{id}/overlay-link` | Новый `{token}`, старый отозван |
| POST | `/uploads` | multipart поле `file`; `{id}` |
| GET | `/uploads/{id}` | PNG, общий доступ |

`GameView`: `{id,tournamentId,tournamentName,tableNumber,gameNumber,revision,updatedAt,state}`. `revision` — ожидаемая версия, а не счётчик, назначаемый клиентом. Несовпадение даёт 409. Номер стола 1–100, партия 1–10000, раунд 1–100. Места ровно 1–10 без повторов. Состав ролей допускает промежуточное редактирование, поэтому правило «6 мирных + 2 мафии + Дон + Шериф» автоматически не навязывается.

### Поля state

- `players`: десять объектов `{seat,name,role,status,pu,revealRole,photo,importedPhoto,testament}`. `photo` и `importedPhoto` — UUID загрузки, не внешняя ссылка. `importedPhoto` зарезервировано для адаптера.
- `role`: `CIVILIAN | MAFIA | DON | SHERIFF`; `UNKNOWN` используется только в публичной проекции.
- `status`: `ALIVE | SHOT | VOTED`. ПУ — производный флаг единственного `firstDeparted`; независимо назначить несколько ПУ нельзя.
- `speaker`: номер или `null`. Выбывшему игроку нельзя передать слово; UI автоматически снимает выступление при выбывании.
- `phase`: `PREPARATION | DAY | VOTING | NIGHT | FINISHED`.
- `round`: номер раунда; `nominees`: уникальные номера в хронологии выставления.
- `testament`: `null` либо `{mode:'COLORS',colors:[{seat,color}],wheel:null}` (до трёх разных мест; цвет `CIVILIAN`/`MAFIA`) либо `{mode:'WHEEL',wheel:1,colors:[]}`.
- `checks`: `{id,by,seat,result,round,published}`. `checker` — номер назначенного Дона или Шерифа, роль `by` определяется сервером. Результат сервер вычисляет по роли цели: `by=DON`: `SHERIFF | NOT_SHERIFF`; `by=SHERIFF`: `RED | BLACK`. Переданный клиентом `result` игнорируется. Массив сохраняет хронологию добавления.
- `showRoles`, `showChecks`: общие разрешения публичного показа всех ролей и всех проверок. Флаги `revealRole` и `published` устарели и больше не участвуют в разрешении показа.
- `theme`: `{civilian,mafia,sheriff,don,transparent,width,height,useRolePhotos,rolePhotos}`. `special` сохранено как устаревший источник цвета Шерифа для миграции старых настроек. Цвета `#RRGGBB`, ширина 640–3840, высота 360–2160. `rolePhotos` — отображение роли в UUID загрузки.

## Публичные маршруты

| Метод | Путь | Назначение |
|---|---|---|
| GET | `/public/games/{id}?token=…` | Только публичный снимок, Cache-Control: no-store |
| GET | `/public/games/{id}/images/{imageId}?token=…` | Только фото, используемое текущей публичной проекцией |
| GET | `/integration/status` | Статус `UNVERIFIED`, список подтверждённых полей пуст |
| GET | `/health` | `{status:'ok'}`; готовность HTTP, не полный мониторинг БД |

В публичном ответе скрытые роли заменены на `UNKNOWN`, неопубликованные проверки отсутствуют, ролевые фото и секреты не передаются. Неверный/отозванный токен — 404. Токен нужен для OBS-маршрутов; API управления `/games/{id}` открыт и возвращает полное состояние.

## WebSocket

- Панель управления: `/ws/games/{id}` без сессии и токена; полное состояние.
- OBS: `/ws/games/{id}?token=…`; проекция с выбранной видимостью ролей и проверок.
- Первое сообщение — полный текущий `GameView`; после сохранений — новый полный снимок. Публичный сокет всегда получает публичную проекцию.
- Клиент отправляет текст `ping` раз в 10 секунд и получает актуальный снимок. Сокет не принимает операции редактирования.
- Повторное подключение через 5 секунд; параллельная HTTP-сверка каждые 10 секунд. Это локальный механизм, не опрос Polemica.
- После отзыва токена старые публичные сокеты закрываются.

Ошибки API имеют безопасное сообщение `{message}`. 400 — валидация, 404 — отсутствующий ресурс или неверный OBS-токен, 409 — конфликт, 413 — превышен размер загрузки, 503 — неподключённый внешний API.

## Игровые команды (10.11.2026)

`POST /games/{id}/actions` — без сессии и CSRF. Тело: `{revision,type,seat?,candidate?}`; результат — актуальный `GameView`. Несовпадение ревизии возвращает 409.

Типы: `PHASE_NEXT`, `PHASE_PREVIOUS`, `SPEECH_START` (seat), `SPEECH_NEXT`, `SPEECH_PAUSE`, `SPEECH_RESUME`, `VOTE` (seat — голосующий; candidate — за кого; null снимает голос), `VOTE_ELIMINATE` (seat — выбывший кандидат). Переходы фаз и управление часами через произвольный PUT запрещены: PUT остаётся для редактирования игроков, ЛХ, проверок, кандидатур и оформления.

Новые поля state: `rounds` (ключ — номер раунда; `{nominees,votes,eligibleVoters,spokenSeats,eliminated}`), `firstDeparted` (один номер или null), `winner` (`CITY`/`MAFIA`/null), `phaseBeforeFinish`, `speechEndsAt` (Unix milliseconds/null), `speechRemainingMs`. `votes` — отображение номера голосующего в номер кандидатуры, поэтому голос игрока уникален. `eligibleVoters` фиксирует состав на начало голосования, чтобы исправление исторических голосов было возможно после выбывания участника.

Полный набор назначенных ролей проверяется при начале партии. Победа рассчитывается для корректного состава (6 мирных, 2 мафии, Дон, Шериф); это не турнирный скоринг. Старый плоский список `nominees` сохранён как проекция текущего раунда.
