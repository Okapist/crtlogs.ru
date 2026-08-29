# Деплой crtlogs.ru

Схема: **GitHub → Actions (тесты + bootJar) → SSH на сервер → systemd**, снаружи —
**Caddy** с автоматическим HTTPS от Let's Encrypt, приложение слушает только 127.0.0.1:8090.

```
Интернет ──443──▶ Caddy (сертификат Let's Encrypt, автопродление)
                      │ reverse_proxy
                      ▼
                 127.0.0.1:8090 — crtlogs.service (java -jar /opt/crtlogs/crtlogs.jar)
                      │
                 /opt/crtlogs/data/ — файловая H2 + trust-сертификаты Минцифры
```

Один workflow (`.github/workflows/ci.yml`) собирает jar и он же его деплоит: артефакт
собирается один раз, поэтому на прод не может уехать сборка, которую никто не тестировал.

## 1. Подготовка сервера (однократно, Ubuntu 22.04/24.04)

### 1.1. Пакеты, пользователь, каталоги

```bash
sudo apt update
sudo apt install -y openjdk-21-jre-headless curl
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install -y caddy

# системный пользователь приложения и каталог данных
# (каталог data обязан существовать: юнит открывает на запись именно его)
sudo useradd -r -s /usr/sbin/nologin crtlogs || true
sudo mkdir -p /opt/crtlogs/data
sudo chown -R crtlogs:crtlogs /opt/crtlogs

# наружу — только 80/443 и ssh
sudo ufw allow OpenSSH && sudo ufw allow 80,443/tcp && sudo ufw --force enable
```

### 1.2. Trust-сертификаты Минцифры

Без них логи digital.gov.ru не синкаются: их серверы не присылают промежуточный
сертификат. Файлы лежат в `data/` на машине разработки и в git не попадают.
**Команды выполняются на машине разработки:**

```bash
scp data/russian_trusted_root_ca.cer data/russian_trusted_sub_ca_2024.crt user@server:/tmp/
ssh user@server 'sudo install -o crtlogs -g crtlogs -m 644 /tmp/russian_trusted_* /opt/crtlogs/data/'
```

### 1.3. Токен `/api/logs`

Токен кладём в отдельный файл, а не в `Environment=` юнита: юнит-файлы читаются всеми,
и `systemctl cat crtlogs` показал бы токен любому пользователю сервера.

```bash
sudo mkdir -p /etc/crtlogs
printf 'CT_AUTH_TOKEN=%s\n' "$(openssl rand -hex 24)" | sudo tee /etc/crtlogs/env >/dev/null
sudo chown root:crtlogs /etc/crtlogs/env && sudo chmod 640 /etc/crtlogs/env
sudo cat /etc/crtlogs/env   # запишите значение себе
```

Без этого файла приложение стартует, но `/api/logs` отвечает 401 всем — это штатный
режим «эндпоинт закрыт».

### 1.4. systemd и Caddy

```bash
sudo cp deploy/crtlogs.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo cp deploy/Caddyfile /etc/caddy/Caddyfile      # нужны A-записи crtlogs.ru и www -> IP
sudo systemctl enable --now caddy
```

Проверить, что ограничения юнита применились: `systemd-analyze security crtlogs`.

### 1.5. Привилегированный скрипт деплоя и sudo

CI не получает общий `sudo`: деплой-пользователю разрешена ровно одна команда. Иначе
SSH-ключ из GitHub фактически равнялся бы root на сервере.

```bash
sudo install -o root -g root -m 755 deploy/crtlogs-deploy /usr/local/sbin/crtlogs-deploy
echo 'DEPLOY_USER ALL=(root) NOPASSWD: /usr/local/sbin/crtlogs-deploy' \
  | sudo tee /etc/sudoers.d/crtlogs-deploy
sudo chmod 440 /etc/sudoers.d/crtlogs-deploy
sudo visudo -c        # проверка синтаксиса перед тем, как выйти из сессии
```

Замените `DEPLOY_USER` на пользователя, под которым ходит CI (значение `SSH_USER`).
Скрипт сам ставит jar, перезапускает сервис, ждёт ответа и **откатывается на предыдущую
версию**, если новая не поднялась. Обновлять его на сервере нужно вручную — на то он и
привилегированный.

## 2. Первый запуск

```bash
./gradlew bootJar
ssh user@server 'sudo /usr/local/sbin/crtlogs-deploy' < build/libs/crtlogs.ru-*.jar
ssh user@server 'sudo systemctl enable crtlogs'
```

Проверка: `https://crtlogs.ru/` (сертификат Let's Encrypt Caddy получит при первом
обращении), логи — `journalctl -u crtlogs -f`, статус синка — `https://crtlogs.ru/api/status`.

## 3. Автодеплой с GitHub

1. **Ключ деплоя** — `ssh-keygen -t ed25519 -f deploy_key -N ''`; публичную часть
   (`deploy_key.pub`) в `~/.ssh/authorized_keys` деплой-пользователя на сервере.
2. **Отпечаток сервера** — `ssh-keyscan -p 22 crtlogs.ru` (вывод пойдёт в секрет ниже).
   Без него CI пришлось бы отключить проверку хоста, и приватный ключ уехал бы первому,
   кто перехватит DNS.
3. **Секреты** (Settings → Secrets and variables → Actions → *Secrets*):

   | Секрет             | Значение                                     |
   |--------------------|----------------------------------------------|
   | `SSH_HOST`         | `crtlogs.ru` или IP                          |
   | `SSH_USER`         | деплой-пользователь из п. 1.5                |
   | `SSH_KEY`          | содержимое `deploy_key` (приватная часть)    |
   | `SSH_KNOWN_HOSTS`  | вывод `ssh-keyscan` из п. 2                  |

4. **Переменные** (там же, вкладка *Variables*):

   | Переменная        | Значение                                                |
   |-------------------|---------------------------------------------------------|
   | `DEPLOY_ENABLED`  | `true` — включить автодеплой при push в `master`         |
   | `SSH_PORT`        | нестандартный порт ssh (необязательно, по умолчанию 22)  |

5. Готово: push в `master` прогоняет тесты и раскатывает jar. Ручной запуск —
   Actions → **ci** → Run workflow (работает всегда, даже при `DEPLOY_ENABLED=false`).

Обновление = `git push`. **Откат** — Actions → ci → Run workflow с выбором старой
ветки/тега; кроме того, если новая версия не отвечает после рестарта, сервер
откатывается на предыдущий jar сам.

## 4. Эксплуатация

- **Бэкап БД**: H2-файл нельзя копировать на живую — только при остановленном сервисе:
  `sudo systemctl stop crtlogs && sudo tar -C /opt/crtlogs -czf /root/ctlog-$(date +%F).tgz data && sudo systemctl start crtlogs`
  (повесить на cron ночью; пара секунд простоя).
- **Токен `/api/logs`**: меняется в `/etc/crtlogs/env` + `sudo systemctl restart crtlogs`.
  Пустое значение или отсутствие файла = эндпоинт закрыт (401 для всех).
- **Логи**: `journalctl -u crtlogs -n 200`; ошибки синка по логам — `/api/logs` с токеном;
  доступы — `/var/log/caddy/crtlogs.log`.
- **Сертификат**: продлевается Caddy автоматически (`systemctl status caddy` при проблемах,
  проверьте, что 80/443 открыты).
- **Диагностика деплоя**: скрипт печатает последние 50 строк журнала сервиса, если тот
  не поднялся, — они видны прямо в логе GitHub Actions.
