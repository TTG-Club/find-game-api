# Find Game API

Микросервис на Java 21 для публикации и поиска игр. Первый контракт поддерживает системы
`DND_2024` и `DND_2014`, форматы `ONLINE`/`OFFLINE` и публичные/приватные игры.

## Стек

- Java 21, Spring Boot 4.1
- PostgreSQL, Spring Data JPA, Liquibase
- MapStruct
- Lombok
- springdoc-openapi (Swagger UI)
- Zalando Logbook

## Запуск

```bash
docker compose up -d
export AUTH_SERVICE_JWT_SECRET="<тот же секрет длиной не менее 32 байт, что и в auth-service>"
export INTERNAL_SERVICE_SECRET="<общий секрет внутренних вызовов>"
mvn spring-boot:run
```

В PowerShell переменная задаётся командой
`$env:AUTH_SERVICE_JWT_SECRET = "<секрет auth-service>"`. Для внутренних вызовов аналогично
задаётся `$env:INTERNAL_SERVICE_SECRET = "<межсервисный секрет>"`.

- API: `http://localhost:8080/api/v1/games`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Health check: `http://localhost:8080/actuator/health`

Сервис проверяет access-токены, выпущенные auth-service. Общий HMAC-секрет передаётся через
`AUTH_SERVICE_JWT_SECRET` и должен совпадать с секретом auth-service.

Как и в comments-api, будущие межсервисные маршруты `/api/v1/internal/**` не используют
пользовательский JWT. Они защищены заголовком `X-Service-Token`, который должен совпадать с
`INTERNAL_SERVICE_SECRET`. Пустой или отсутствующий серверный секрет закрывает такие маршруты.

## Профили пользователя

У каждого пользователя есть общая информация и два обязательных профиля: Мастера и Игрока.
Они автоматически создаются при первом `GET /api/v1/profiles/me`. Идентификатор пользователя
берётся только из `sub` access-токена.

Обновление выполняется запросом `PUT /api/v1/profiles/me`:

```json
{
  "birthYear": 1990,
  "gender": "MALE",
  "tabletopExperienceYears": 7,
  "master": {
    "about": "Вожу сюжетные кампании"
  },
  "player": {
    "about": "Люблю исследование и отыгрыш"
  }
}
```

`gender` принимает `MALE`, `FEMALE`, `OTHER` или `NOT_SPECIFIED`. Год рождения, пол и опыт
являются общей частью. Поля `master.about` и `player.about` независимы и ограничены 5000
символами. Опыт в НРИ задаётся целым количеством лет от 0 до 100.

## Пример создания

```bash
curl -X POST http://localhost:8080/api/v1/games \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <access-token>" \
  -d '{
    "title": "Проклятие Страда",
    "system": "DND_2024",
    "imageUrl": "https://cdn.example.org/games/strahd.jpg",
    "virtualTableUrl": "https://vtt.example.org/games/curse-of-strahd",
    "genre": "Готическое фэнтези",
    "description": "Готическая кампания по Равенлофту",
    "requirements": "18+, стабильное участие по субботам",
    "allowedSources": ["Player's Handbook 2024", "Tasha's Cauldron of Everything"],
    "type": "ONLINE",
    "playersToStart": 3,
    "maxPlayers": 5,
    "minAge": 18,
    "maxAge": 99,
    "startingLevel": 1,
    "crossplayAllowed": true,
    "durationType": "CAMPAIGN",
    "costType": "PAID",
    "visibility": "PRIVATE"
  }'
```

Для приватной игры ответ создания содержит `inviteCode`. Получить её можно по
`GET /api/v1/games/{id}?inviteCode={inviteCode}`. В выдачу поиска попадают только публичные игры;
код приглашения никогда не возвращается из `GET`-методов.

`durationType` принимает `ONE_SHOT` или `CAMPAIGN`. В игре обязательно указывается только тип
стоимости: `costType: FREE` или `costType: PAID`. Конкретная сумма и условия оплаты задаются
отдельно для каждой сессии.

Для `OFFLINE` можно передать город, например `"city": "Кишинёв"`; у `ONLINE` поле `city`
запрещено. Возрастные ограничения независимы: можно передать только `minAge` (например, не
младше 18 лет), только `maxAge` (не старше 30 лет), обе границы либо не передавать их вовсе.
Если указаны обе границы, `minAge` не может превышать `maxAge`.
Стартовый уровень персонажей задаётся полем `startingLevel` от 1 до 20.
Поле `crossplayAllowed` разрешает (`true`) или запрещает (`false`) игроку выбрать персонажа
другого пола. Если поле не передано, используется `false`.

Используется постмодерация: при создании игра сразу получает статус `OPEN`. Возможные статусы:
`DRAFT`, `OPEN`, `CLOSED`. Поля `createdAt` и `updatedAt` устанавливаются сервером.

Пользователь с JWT-ролью `ADMIN` или `MODERATOR` может скрыть нарушающую правила игру:

```http
DELETE /api/v1/games/{gameId}
Authorization: Bearer <access-token>
Content-Type: application/json

{
  "reason": "Нарушение правил сообщества"
}
```

Используется мягкое удаление: сервер устанавливает `deletedAt`, после чего игра исчезает из
публичного поиска, недоступна по прямой ссылке и invite-коду. Сессии, заявки, игроки и листы
персонажей физически остаются в PostgreSQL, но недоступны через обычный API. Обычный
пользователь, включая мастера-владельца без административной роли, выполнить операцию не может.
Тело запроса необязательно. Если причина передана, `reason` должна содержать от 1 до 1000
символов; она сохраняется в игре как `deletionReason` для административного аудита и не
возвращается публичным API.

`allowedSources` — необязательный набор допустимых книг и других источников. Можно передать до
50 уникальных названий длиной до 120 символов каждое.

## Поиск

```text
GET /api/v1/games?system=DND_2024&type=ONLINE&page=0&size=20
```

Результат постраничный и отсортирован от новых игр к старым.

## Сессии игры

Создать сессию может только мастер-владелец игры. Идентификатор мастера берётся из `sub`
access-токена, передавать его в теле запроса не нужно.

```bash
curl -X POST http://localhost:8080/api/v1/games/{gameId}/sessions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <access-token>" \
  -d '{
    "title": "Знакомство с Баровией",
    "startsAt": "2026-09-05T16:00:00Z",
    "estimatedDurationMinutes": 240,
    "priceAmount": 15.00,
    "priceCurrency": "EUR",
    "paymentType": "PREPAYMENT"
  }'
```

Сессия создаётся со статусом `SCHEDULED` и пустым `registeredPlayerIds`. Остальные статусы:
`IN_PROGRESS` и `COMPLETED`. Количество сессий не ограничено ни для `ONE_SHOT`, ни для
`CAMPAIGN`: например, у ваншота можно отдельно создать нулевую и основную сессии.
Необязательное положительное поле `estimatedDurationMinutes` задаёт предполагаемую длительность
сессии в минутах и переносится при копировании сессии.
Получение сессий: `GET /api/v1/games/{gameId}/sessions`; для приватной
игры не-владельцу нужно добавить `?inviteCode={inviteCode}`. Оба метода требуют access-токен.

Для сессии платной игры обязательны положительный `priceAmount`, трёхбуквенный код валюты
ISO 4217 в `priceCurrency` и `paymentType`: `PREPAYMENT` или `POSTPAYMENT`. Для бесплатной
игры эти поля у сессии не передаются.

Мастер может создать следующую сессию кампании копированием предыдущей:

```http
POST /api/v1/games/{gameId}/sessions/{sourceSessionId}/copy
Authorization: Bearer <access-token>
Content-Type: application/json

{
  "startsAt": "2026-09-12T16:00:00Z",
  "title": "Следующая глава"
}
```

`title` необязателен: без него сохраняется название исходной сессии. Стоимость и условия
оплаты также копируются. В новую сессию переносятся только принятые (`APPROVED`) игроки вместе
со ссылками на листы персонажей; ожидающие и отклонённые заявки не копируются. Новая сессия
получает статус `SCHEDULED`, а присутствие каждого перенесённого игрока — `NOT_ATTENDING`.
Копирование доступно как для `CAMPAIGN`, так и для `ONE_SHOT`.

## Заявки игроков

Авторизованный игрок подаёт заявку на запланированную сессию. Ссылка на лист персонажа
необязательна; идентификатор игрока берётся из `sub` access-токена.

```bash
curl -X POST http://localhost:8080/api/v1/games/{gameId}/sessions/{sessionId}/registrations \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <access-token>" \
  -d '{
    "characterSheetUrl": "https://ttg.club/characters/strahd-hunter"
  }'
```

Для приватной игры используется параметр `?inviteCode={inviteCode}`. Новая заявка получает
статус `PENDING`. Повторно подать заявку того же игрока на ту же сессию нельзя.

Мастер-владелец получает заявки, включая ссылки на листы персонажей:

```text
GET /api/v1/games/{gameId}/sessions/{sessionId}/registrations
```

Решение по заявке:

```http
PATCH /api/v1/games/{gameId}/sessions/{sessionId}/registrations/{registrationId}
Content-Type: application/json

{"decision":"APPROVE"}
```

Второе значение решения — `REJECT`. Статус заявки меняется на `APPROVED` или `REJECTED`.
Только принятые игроки входят в `registeredPlayerIds` сессии; принять игроков сверх
`maxPlayers` невозможно.

После принятия заявки присутствие игрока по умолчанию равно `NOT_ATTENDING` («не буду»).
Сам игрок может изменить его на `ATTENDING` («буду») и обратно:

```http
PATCH /api/v1/games/{gameId}/sessions/{sessionId}/registrations/me/attendance
Authorization: Bearer <access-token>
Content-Type: application/json

{"attendanceStatus":"ATTENDING"}
```

До статуса заявки `APPROVED` менять присутствие нельзя. Мастер видит `attendanceStatus`
в ответах списка заявок.

Для платной игры Мастер может отметить принятого игрока как оплатившего до или после сессии:

```http
PATCH /api/v1/games/{gameId}/sessions/{sessionId}/registrations/{registrationId}/payment
Authorization: Bearer <access-token>
Content-Type: application/json

{"paid":true}
```

В ответе заявки возвращаются `paid` и время `paidAt`. Ограничений по статусу самой сессии нет.
Повторная отметка сохраняет исходное время оплаты. Для исправления ошибки можно передать
`{"paid":false}`. В бесплатных играх и для непринятых заявок операция недоступна. При
копировании сессии отметка оплаты в новую сессию не переносится.
