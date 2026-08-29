# crtlogs.ru

[![CI](https://github.com/Okapist/crtlogs.ru/actions/workflows/ci.yml/badge.svg)](https://github.com/Okapist/crtlogs.ru/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.16-6DB33F)
![Gradle](https://img.shields.io/badge/Gradle-9.7.1-02303A?logo=gradle&logoColor=white)
![Jetty](https://img.shields.io/badge/Jetty-embedded-yellow)
![H2](https://img.shields.io/badge/DB-H2%20file-blue)

Минималистичный аналог [crt.sh](https://crt.sh) для **российских CT-логов**
(Certificate Transparency): шесть логов трёх операторов — Yandex (Agate 2026/2027),
VK (NCA2026/2027) и Минцифры (2026/2027). Приложение само синхронизирует записи
логов, складывает их в локальную БД и отдаёт поисковый API и веб-интерфейс,
совместимые по контракту с crt.sh.

## Возможности

- **Инкрементальный синк** всех логов из
  [ctlog.json](https://browser-resources.s3.yandex.net/ctlog/ctlog.json)
  (запуск при старте + каждые 10 минут, прогресс переживает перезапуски);
- **API как у crt.sh**: `/?q=...&output=json`, страница сертификата `/?id=`,
  выгрузка PEM `/?d=`, wildcard-поиск поддоменов (`%.example.ru`),
  `exclude=expired`, сортировка `sort`/`dir`;
- **Дедупликация**: один сертификат, поданный CA в несколько логов, — одна строка
  поиска (как у crt.sh); все вхождения видны в CT-таблице на странице сертификата;
- **Пейджинг** по 100 результатов (`&p=N`), `result_count` — общее число совпадений;
- **UI в стиле crt.sh** (vanilla JS, без сборки): результаты на той же странице,
  сертификат — в новой вкладке, openssl-подобный дамп, отпечатки SHA-256/SHA-1;
- кеш поиска и деталей (Caffeine), рейт-лимитер 1 rps + burst (429 c `Retry-After`),
  статус синка `GET /api/status` — публичный, `GET /api/logs` — под Bearer-токеном.

## Стек

Java 21 · Spring Boot 3.5 (Jetty, JDBC — без JPA/Hibernate/Security) · H2 (файловая,
`./data`) · Caffeine · vanilla JS. Сборка — Gradle wrapper, ничего ставить кроме JDK не нужно.

## Локальная сборка и запуск

Требуется JDK 21+ (проверялось на Temurin 21/25).

```bash
./gradlew bootRun          # поднимется на http://localhost:8090
```

Полезное:

- порт меняется `SERVER_PORT=8091 ./gradlew bootRun`;
- БД создаётся в `./data/` автоматически; первый синк стартует сразу после подъёма
  (полная первичная загрузка всех логов занимает несколько минут);
- **логи Минцифры** отдают цепочку без промежуточного сертификата, поэтому для их
  синка в `data/` должны лежать trust-файлы `russian_trusted_root_ca.cer` и
  `russian_trusted_sub_ca_2024.crt` (в git не попадают; см. `DEPLOY.md`). Без них
  остальные четыре лога синкаются, Минцифры — warn в логах;
- при `gradlew bootRun` dev-токен `/api/logs` — `e2e-token` (переопределяется
  переменной `CT_AUTH_TOKEN`).

Тесты:

```bash
./gradlew test
```

## API — примеры

```bash
# поиск (JSON, как у crt.sh); вернётся страница 1 из 100 строк
curl 'http://localhost:8090/?q=example.ru&output=json'

# поддомены (wildcard: % в URL кодируется как %25), только действующие, сортировка, страница
curl 'http://localhost:8090/?q=%25.example.ru&exclude=expired&sort=1&dir=desc&p=2&output=json'

# страница сертификата (HTML) и выгрузка PEM
curl 'http://localhost:8090/?id=42'
curl -OJ 'http://localhost:8090/?d=42'

# статус синка (публичный) и полные детали (под токеном)
curl 'http://localhost:8090/api/status'
curl -H 'Authorization: Bearer <токен>' 'http://localhost:8090/api/logs'
```

## Разворачивание

Прод-схема: **GitHub Actions** (тесты + bootJar) → **SSH на сервер** →
**systemd-сервис** (127.0.0.1:8090), снаружи — **Caddy** с автоматическим
HTTPS от Let's Encrypt. Полный runbook — в [DEPLOY.md](DEPLOY.md), конфиги —
в [deploy/](deploy/) и [.github/workflows/](.github/workflows/):

- `.github/workflows/ci.yml` — тесты, сборка и деплой одним прогоном: артефакт
  собирается один раз и он же уезжает на сервер;
- `deploy/crtlogs-deploy` — привилегированный скрипт установки на сервере:
  ставит jar, ждёт, пока сервис ответит, и откатывается, если не дождался
  (CI разрешена ровно эта команда через `sudo`, а не `sudo` вообще);
- `deploy/crtlogs.service` — systemd-юнит с ограничением прав, токен `/api/logs`
  подключается из `/etc/crtlogs/env`, а не лежит в самом юните;
- `deploy/Caddyfile` — reverse-proxy с TLS и перезаписью `X-Forwarded-For`;
- `application-prod.yml` — прод-профиль: слушать только localhost, graceful shutdown,
  реальный IP клиента из `X-Forwarded-For` (за прокси).

Быстрый ручной вариант: `./gradlew bootJar`, поставить юнит и Caddyfile по DEPLOY.md,
затем `ssh user@server 'sudo /usr/local/sbin/crtlogs-deploy' < build/libs/crtlogs.ru-*.jar`.
