# Деплой crtlogs.ru

Схема: **GitHub → Actions (тесты + bootJar) → SSH/SCP на сервер → systemd**, снаружи —
**Caddy** с автоматическим HTTPS от Let's Encrypt, приложение слушает только 127.0.0.1:8090.

```
Интернет ──443──▶ Caddy (сертификат Let's Encrypt, автопродление)
                      │ reverse_proxy
                      ▼
                 127.0.0.1:8090 — crtlogs.service (java -jar /opt/crtlogs/crtlogs.jar)
                      │
                 /opt/crtlogs/data/ — файловая H2 + trust-сертификаты Минцифры
```

## 1. Подготовка сервера (однократно, Ubuntu 22.04/24.04)

```bash
# Java 21 и Caddy (официальный репозиторий)
sudo apt update
sudo apt install -y openjdk-21-jre-headless
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install -y caddy

# пользователь и каталог приложения
sudo useradd -r -s /usr/sbin/nologin crtlogs || true
sudo mkdir -p /opt/crtlogs/data
sudo chown -R crtlogs:crtlogs /opt/crtlogs

# trust-сертификаты Минцифры — БЕЗ них логи digital.gov.ru не синкаются
# (файлы лежат в data/ на машине разработки и не попадают в git)
sudo scp data/russian_trusted_root_ca.cer data/russian_trusted_sub_ca_2024.crt user@server:/tmp/
sudo install -o crtlogs -g crtlogs -m 644 /tmp/russian_trusted_* /opt/crtlogs/data/

# systemd-сервис
sudo cp deploy/crtlogs.service /etc/systemd/system/
# задайте токен /api/logs (sudo openssl rand -hex 24) — раскомментируйте строку с CT_AUTH_TOKEN
sudo nano /etc/systemd/system/crtlogs.service
sudo systemctl daemon-reload

# Caddy: HTTPS (нужна DNS A-запись crtlogs.ru -> IP сервера, порты 80/443 открыты)
sudo cp deploy/Caddyfile /etc/caddy/Caddyfile
sudo systemctl enable --now caddy
```

## 2. Первый запуск

Соберите jar на машине разработки и положите на сервер:

```bash
./gradlew bootJar
scp build/libs/crtlogs.ru-*.jar user@server:/tmp/
ssh user@server 'sudo install -o crtlogs -g crtlogs -m 644 /tmp/crtlogs.ru-*.jar /opt/crtlogs/crtlogs.jar'
ssh user@server 'sudo systemctl enable --now crtlogs'
```

Проверка: `https://crtlogs.ru/` (сертификат Let's Encrypt Caddy получит при первом
обращении), логи — `journalctl -u crtlogs -f`, статус синка — `https://crtlogs.ru/api/status`.

## 3. Автодеплой с GitHub

1. Ключ деплоя: `ssh-keygen -t ed25519 -f deploy_key` (без passphrase);
   публичную часть — в `~/.ssh/authorized_keys` на сервере.
2. Секреты репозитория (Settings → Secrets and variables → Actions):
   `SSH_HOST`, `SSH_USER`, `SSH_KEY` (содержимое `deploy_key`), при необходимости `SSH_PORT`.
3. Включить автодеплой: там же, вкладка **Variables** → `DEPLOY_ENABLED` = `true`
   (без этого workflow `deploy` запускается только вручную).
4. Готово: push в `master` прогоняет тесты и раскатывает jar, либо
   Actions → deploy → Run workflow — вручную.

Обновление = `git push`; откат — повторный запуск workflow со старым коммитом
(Actions → deploy → Run workflow → выбор ветки/тега).

## 4. Эксплуатация

- **Бэкап БД**: H2-файлы нельзя копировать на живую — только при остановленном сервисе:
  `sudo systemctl stop crtlogs && sudo tar -C /opt/crtlogs -czf /root/ctlog-$(date +%F).tgz data && sudo systemctl start crtlogs`
  (повесить на cron ночью; пара секунд простоя).
- **Токен /api/logs**: меняется в `Environment=CT_AUTH_TOKEN` юнита + `systemctl restart crtlogs`.
  Пустое значение = эндпоинт закрыт (401 для всех).
- **Логи**: `journalctl -u crtlogs -n 200`; синк-ошибки по логам — `/api/logs` с токеном.
- **Сертификат**: продлевается Caddy автоматически, ничего делать не нужно
  (`sudo systemctl status caddy` при проблемах; проверка портов 80/443 в фаерволе).
