# Деплой crtlogs.ru

Схема: **GitHub → Actions (тесты + bootJar) → SSH → systemd**, снаружи — **Caddy**
(Let's Encrypt, автопродление); приложения слушают только localhost.

```
Интернет ──443──▶ Caddy (сертификат Let's Encrypt, автопродление)
                      │
                      ├── /testing/* ─▶ 127.0.0.1:8091 — crtlogs-testing.service
                      │                 /testing/opt/crtlogs/  (своя H2, закрыт токеном)
                      │
                      └── всё прочее ─▶ 127.0.0.1:8090 — crtlogs.service
                                        /opt/crtlogs/          (своя H2)
```

Два окружения на одной машине:

| | Прод | Стенд |
|---|---|---|
| Адрес | `https://crtlogs.ru/` | `https://crtlogs.ru/testing/` (закрыт токеном) |
| Порт | 8090 | 8091 |
| Каталоги | `/opt/crtlogs`, `/etc/crtlogs` | `/testing/opt/crtlogs`, `/testing/etc/crtlogs` |
| Юнит | `crtlogs` | `crtlogs-testing` |
| Раскатка | **только вручную**: Actions → `deploy-production` | автоматически при push в `master` |

Push в `master` раскатывает только стенд; ни push, ни merge, ни зелёный CI на прод не
ведут. Сертификаты УЦ Минцифры лежат внутри jar (`src/main/resources/trust/`), ставить
на сервере ничего не нужно.

## 1. Подготовка сервера (однократно, Ubuntu 22.04/24.04)

Файлы юнитов, Caddyfile и скрипт установки — в каталоге `deploy/` этого репозитория.
Скопируйте его на сервер с машины разработки:

```bash
rsync -a deploy/ user@crtlogs.ru:~/crtlogs-deploy/     # или scp -r, или git clone
```

Дальше всё на сервере, из этого каталога.

### 1.1. Пакеты, пользователи, каталоги

```bash
cd ~/crtlogs-deploy

sudo apt update
sudo apt install -y openjdk-25-jre-headless curl

# Caddy из официального репозитория
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install -y caddy

# пользователи и каталоги данных; data обязан существовать до старта — юнит открывает его на запись
sudo useradd -r -s /usr/sbin/nologin crtlogs || true
sudo mkdir -p /opt/crtlogs/data && sudo chown -R crtlogs:crtlogs /opt/crtlogs

sudo useradd -r -s /usr/sbin/nologin crtlogs-testing || true
sudo mkdir -p /testing/opt/crtlogs/data && sudo chown -R crtlogs-testing:crtlogs-testing /testing/opt/crtlogs

# наружу — только 80/443 и ssh
sudo ufw allow OpenSSH && sudo ufw allow 80,443/tcp && sudo ufw --force enable
```

### 1.2. Токены

Токен — в отдельном файле, не в `Environment=` юнита: юнит-файл читается всеми
(`systemctl cat`). Нет файла или пусто — `/api/logs` отвечает 401 всем, это штатный
режим «эндпоинт закрыт».

Токен прода — для `/api/logs`:

```bash
sudo mkdir -p /etc/crtlogs
printf 'CT_AUTH_TOKEN=%s\n' "$(openssl rand -hex 24)" | sudo tee /etc/crtlogs/env >/dev/null
sudo chown root:crtlogs /etc/crtlogs/env && sudo chmod 640 /etc/crtlogs/env
sudo cat /etc/crtlogs/env   # запишите значение
```

Токен стенда — другой, закрывает стенд целиком (логин любой, пароль = токен); открыт
только `/testing/api/status`, по нему деплой проверяет живость:

```bash
sudo mkdir -p /testing/etc/crtlogs
printf 'CT_AUTH_TOKEN=%s\n' "$(openssl rand -hex 24)" | sudo tee /testing/etc/crtlogs/env >/dev/null
sudo chown root:crtlogs-testing /testing/etc/crtlogs/env && sudo chmod 640 /testing/etc/crtlogs/env
```

Токены обязаны отличаться: стенд обновляется с каждого push, утечка его токена не
должна открывать `/api/logs` прода.

### 1.3. systemd и Caddy

```bash
sudo cp crtlogs.service crtlogs-testing.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo cp Caddyfile /etc/caddy/Caddyfile      # нужны A-записи crtlogs.ru и www -> IP

# Caddy работает под пользователем caddy, каталог логов должен принадлежать ему
sudo mkdir -p /var/log/caddy && sudo chown caddy:caddy /var/log/caddy

# apt уже запустил Caddy с конфигом по умолчанию — конфиг перечитывается reload'ом
sudo systemctl enable caddy
sudo systemctl reload caddy
```

Проверки:

- `sudo ss -lntp | grep :443` — конфиг действительно применился: неудачный reload не
  роняет Caddy, сервис остаётся `active` со старым конфигом;
- `caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile` — синтаксис; два
  предупреждения `Unnecessary header_up X-Forwarded-For` — штатные;
- `systemd-analyze security crtlogs crtlogs-testing` — ограничения юнитов.

Если `crtlogs.log: permission denied` при правильном каталоге — файл лога создан от
root (запускали `caddy run`/`caddy validate` через sudo); `sudo rm
/var/log/caddy/crtlogs.log && sudo systemctl reload caddy`, Caddy создаст файл сам.

### 1.4. sudo для деплоя

Общего sudo у CI нет: два деплой-пользователя, каждому разрешена ровно одна команда —
поэтому ключ стенда физически не может поставить прод.

```bash
sudo useradd -m deploy-testing || true
sudo useradd -m deploy-prod || true

sudo install -o root -g root -m 755 crtlogs-deploy /usr/local/sbin/crtlogs-deploy
printf '%s\n%s\n' \
  'deploy-testing ALL=(root) NOPASSWD: /usr/local/sbin/crtlogs-deploy testing' \
  'deploy-prod    ALL=(root) NOPASSWD: /usr/local/sbin/crtlogs-deploy production' \
  | sudo tee /etc/sudoers.d/crtlogs-deploy
sudo chmod 440 /etc/sudoers.d/crtlogs-deploy
sudo visudo -c        # проверка синтаксиса до выхода из сессии
```

Скрипт ставит jar, перезапускает сервис, ждёт ответа и откатывается на предыдущую
версию, если новая не поднялась. Обновляется на сервере вручную.

## 2. Первый запуск

```bash
./gradlew bootJar        # на машине разработки
scp build/libs/crtlogs.ru-*.jar user@crtlogs.ru:~/crtlogs.jar
ssh -t user@crtlogs.ru 'sudo /usr/local/sbin/crtlogs-deploy production < ~/crtlogs.jar'
ssh -t user@crtlogs.ru 'sudo /usr/local/sbin/crtlogs-deploy testing    < ~/crtlogs.jar'
```

Здесь ещё можно ходить своей учёткой с sudo; деплой-пользователи нужны только для CI.
Автозапуск после перезагрузки — на сервере:

```bash
sudo systemctl enable crtlogs crtlogs-testing
```

Проверка: `https://crtlogs.ru/` и `https://crtlogs.ru/testing/` (спросит логин и пароль —
пароль равен токену стенда). Сертификат Let's Encrypt Caddy получит при первом обращении.

## 3. Автодеплой с GitHub

Ключи — два, по одному на окружение. На машине разработки:

```bash
ssh-keygen -t ed25519 -f deploy_key_testing -N ''
ssh-keygen -t ed25519 -f deploy_key_prod    -N ''
scp deploy_key_testing.pub deploy_key_prod.pub user@crtlogs.ru:~/
```

На сервере — публичные части от root (паролей у деплой-учёток нет, войти под ними
самим не получится):

```bash
for u in deploy-testing deploy-prod; do
  sudo install -d -m 700 -o $u -g $u /home/$u/.ssh
done
sudo install -m 600 -o deploy-testing -g deploy-testing \
  ~/deploy_key_testing.pub /home/deploy-testing/.ssh/authorized_keys
sudo install -m 600 -o deploy-prod -g deploy-prod \
  ~/deploy_key_prod.pub /home/deploy-prod/.ssh/authorized_keys
```

Отпечаток сервера (без него CI пришлось бы отключить проверку хоста):

```bash
ssh-keyscan -p 22 crtlogs.ru   # ровно те хост и порт, что пойдут в SSH_HOST и SSH_PORT:
                               # для IP или другого порта known_hosts ищется по ним
```

Секреты (Settings → Secrets and variables → Actions → *Secrets*):

| Секрет            | Значение |
|-------------------|----------|
| `SSH_HOST`        | `crtlogs.ru` или IP — общий |
| `SSH_KNOWN_HOSTS` | вывод `ssh-keyscan` — общий |
| `SSH_USER`        | `deploy-testing` в окружении staging, `deploy-prod` в production |
| `SSH_KEY`         | соответствующий приватный ключ — там же |

`SSH_USER` и `SSH_KEY` кладите в окружения **staging** и **production**
(Settings → Environments), не на уровень репозитория: значения разные, имена одинаковые.

Переменные (там же, вкладка *Variables*): `DEPLOY_ENABLED=true` — включить автодеплой
при push в `master`; `SSH_PORT` — нестандартный порт ssh, необязательно.

В окружение `production` добавьте **Required reviewers** — раскатка прода будет ждать
подтверждения после нажатия Run workflow.

Откат прода — тот же workflow `deploy-production` со старым тегом; плюс автоматический
откат на сервере, если версия не поднялась.

## 4. Эксплуатация

| Вопрос | Ответ |
|---|---|
| Бэкап БД | H2 нельзя копировать на живую, только при остановленном сервисе: `sudo systemctl stop crtlogs && sudo tar -C /opt/crtlogs -czf /root/ctlog-$(date +%F).tgz data && sudo systemctl start crtlogs` (повесить на cron ночью; пара секунд простоя) |
| Сменить токен `/api/logs` | `/etc/crtlogs/env` + `sudo systemctl restart crtlogs`; пусто или нет файла — эндпоинт закрыт (401 всем) |
| Логи приложения | `journalctl -u crtlogs -n 200` |
| Ошибки синхронизации | `/api/logs` с токеном |
| Доступы (access-лог) | `/var/log/caddy/crtlogs.log` |
| Сертификат | продлевает Caddy сам; ошибки — в `journalctl -u caddy`, сервис они не роняют |
| Правка Caddyfile | `sudo systemctl reload caddy` + `systemctl status caddy`; неудачный reload внешне незаметен, см. п. 1.3 |
| CSP | `deploy/Caddyfile`: свой домен, шрифты Google, `mc.yandex.ru`. После правки — консоль браузера: нарушения выглядят как «пропал шрифт» или «перестал считать счётчик» |
| Деплой не поднялся | скрипт печатает последние 50 строк журнала сервиса прямо в лог Actions |
