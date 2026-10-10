# crtlogs.ru

[![CI](https://github.com/Okapist/crtlogs.ru/actions/workflows/ci.yml/badge.svg)](https://github.com/Okapist/crtlogs.ru/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-25-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F)
![Gradle](https://img.shields.io/badge/Gradle-9.7.1-02303A?logo=gradle&logoColor=white)
![H2](https://img.shields.io/badge/DB-H2%20file-blue)

Поисковый сервис по российским логам Certificate Transparency — функциональный аналог
[crt.sh](https://crt.sh). Шесть логов трёх операторов: Yandex (Agate 2026/2027),
VK (NCA2026/2027) и Минцифры (2026/2027). Сервис синхронизирует логи в локальную базу
H2 и даёт поисковый API и веб-интерфейс, совместимые по контракту с crt.sh.

## Возможности

- Синхронизация всех логов из [ctlog.json](https://browser-resources.s3.yandex.net/ctlog/ctlog.json):
  при старте и далее каждые 10 минут; прогресс хранится в базе и переживает перезапуск.
- Поиск в семантике crt.sh: точное имя, поддомены (`%.example.ru`), фильтр действующих,
  сортировка по пяти колонкам, страницы по 100 записей.
- Дедупликация: сертификат, поданный в несколько логов, — одна строка выдачи; все
  вхождения перечислены на странице сертификата.
- Кириллические домены ищутся в любой форме (`%.рф` и `%.xn--p1ai` — одно и то же),
  показываются юникодом; ярлыки со смешанными письменностями остаются punycode.
- Веб-интерфейс без JavaScript: серверный рендеринг, дамп сертификата в формате
  `openssl x509 -text`, отпечатки SHA-256 и SHA-1.
- Кеш поиска и дампов (Caffeine), лимит частоты запросов по IP, публичный `/api/status`.

Стек: Java 25, Spring Boot 4.1 (встроенный Jetty, Spring JDBC без JPA и Spring
Security), Jackson 3, файловая H2, Caffeine. Версии зависимостей задаются спецификацией
Spring Boot.

## Запуск

Достаточно любого JDK 21+; JDK 25 для сборки и тестов Gradle скачает сам (toolchain).

```bash
./gradlew bootRun   # http://localhost:8090
./gradlew test
```

База создаётся в `./data` автоматически, первичная загрузка логов занимает несколько
минут. Сертификаты УЦ Минцифры уже входят в артефакт (`src/main/resources/trust/`),
донастройка не нужна; список переопределяется переменной `CT_TRUST_CERT`.

## API

| Эндпоинт | Назначение |
| --- | --- |
| `GET /?q=<identity>` | Поиск. HTML по умолчанию, JSON при `output=json` |
| `GET /?id=<id>` | Страница сертификата |
| `GET /?d=<id>` | Выгрузка сертификата в PEM |
| `GET /api/certs/{id}` | Детали сертификата в JSON |
| `GET /api/status` | Состояние синхронизации, без авторизации |
| `GET /api/logs` | То же с текстами ошибок; требует `Authorization: Bearer` |

Параметры поиска: `exclude=expired` — только действующие; `sort` (0 — ID, 1 — дата
попадания в лог, 2 — начало срока действия, 3 — издатель, 4 — окончание) и
`dir=asc|desc`; `p` — номер страницы; `result_count` в JSON — общее число совпадений.
Подстановочные знаки `%` и `_`, минимум два символа помимо них: `%.example.ru` —
поддомены, `%vk%` — вхождение. В адресе `%` можно не кодировать, если за ним не идут
две шестнадцатеричные цифры: `?q=%.example.ru` работает как есть, а `%ab` — escape,
такой шаблон пишется `%25ab`.

Ошибки: JSON-клиентам (`output=json`, `/api/*`) — `{"error": "<причина>"}`, браузеру —
главная страница с плашкой и сохранённым запросом; коды ответов настоящие — 400, 404,
429. Лимит частоты: 1 запрос/с с IP, всплеск до 3, сверх — 429 с `Retry-After`.

```bash
curl 'http://localhost:8090/?q=example.ru&output=json'              # поиск, JSON
curl 'http://localhost:8090/?q=%25.example.ru&exclude=expired&p=2'  # поддомены, страница 2
curl 'http://localhost:8090/?id=42'                                 # страница сертификата
curl -OJ 'http://localhost:8090/?d=42'                              # выгрузка PEM
curl 'http://localhost:8090/api/status'                             # состояние синхронизации
```

## Развёртывание

Прод — `https://crtlogs.ru/`, стенд — `https://crtlogs.ru/testing/` (закрыт токеном);
обе на одной машине: systemd + Caddy с сертификатом Let's Encrypt, раскатка из GitHub
Actions по SSH. Push в `master` обновляет только стенд; прод — только вручную workflow
`deploy-production`, при неудачном рестарте сервер сам откатывается на предыдущую
версию. Конфигурация — в [deploy/](deploy/) и [.github/workflows/](.github/workflows/),
полная инструкция — [DEPLOY.md](DEPLOY.md).
