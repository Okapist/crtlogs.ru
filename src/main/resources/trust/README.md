# Доверенные сертификаты УЦ Минцифры

Серверы CT-логов `*.ctlog.digital.gov.ru` присылают только свой листовой сертификат,
без промежуточного, поэтому обычной цепочки не хватает. Эти два файла добавляются к
стандартному cacerts JDK (сам JDK не изменяется, см. `CtHttpClientConfig.trustedSslContext`).

Лежат в classpath и попадают внутрь jar — на сервере ничего доустанавливать не нужно.

| Файл | Subject | Действителен до | SHA-256 |
|---|---|---|---|
| `russian_trusted_root_ca.crt` | CN=Russian Trusted Root CA | 2032-02-27 | `D2:6D:2D:02:31:B7:C3:9F:92:CC:73:85:12:BA:54:10:35:19:E4:40:5D:68:B5:BD:70:3E:97:88:CA:8E:CF:31` |
| `russian_trusted_sub_ca_2024.crt` | CN=Russian Trusted Sub CA | 2029-07-19 | `21:55:78:50:36:C9:00:DB:B5:F1:BB:2A:15:69:C8:0C:55:59:5B:D6:BF:94:86:7A:29:BB:DD:BC:7D:88:A3:F2` |

Оба — публичные сертификаты удостоверяющих центров, приватных ключей не содержат.

## Откуда взяты

- корневой: <https://gu-st.ru/content/lending/russian_trusted_root_ca_pem.crt>
- промежуточный: адрес берётся из расширения AIA боевого сертификата лога —
  <http://nuc-cdp.digital.gov.ru/cdp/subca_ssl_rsa2024.crt>

Обратите внимание: у промежуточных сертификатов 2022 и 2024 годов **одинаковый CN**
`Russian Trusted Sub CA`, но подписан листовой именно вариантом 2024 года. Тот, что
отдают по ссылке `russian_trusted_sub_ca_pem.crt` со страницы Госуслуг, — это версия
2022 года, и с ней проверка не проходит.

## Как обновить, когда истечёт срок

```bash
host=26.ctlog.digital.gov.ru
# 1. узнать, каким промежуточным подписан текущий сертификат лога
echo | openssl s_client -connect $host:443 -servername $host 2>/dev/null \
  | openssl x509 -noout -text | grep -A2 'Authority Information Access'
# 2. скачать его по этому адресу, положить сюда, затем проверить цепочку целиком:
{ cat russian_trusted_root_ca.crt; echo; cat russian_trusted_sub_ca_2024.crt; } > /tmp/bundle.pem
echo | openssl s_client -connect $host:443 -servername $host -CAfile /tmp/bundle.pem 2>&1 \
  | grep 'Verify return code'    # должно быть 0 (ok)
```
