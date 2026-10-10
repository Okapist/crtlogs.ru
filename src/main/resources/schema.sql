CREATE TABLE IF NOT EXISTS ct_log (
  url               VARCHAR(255) PRIMARY KEY, -- базовый URL лога из ctlog.json
  description       VARCHAR(255),
  operator          VARCHAR(255),
  last_synced_index BIGINT NOT NULL DEFAULT 0,
  tree_size         BIGINT NOT NULL DEFAULT 0, -- из последнего get-sth
  last_error        VARCHAR(1024),            -- null если последний синк успешен
  updated_at        TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ca ( -- как в crt.sh: id издателя
  id   BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(1024) NOT NULL UNIQUE -- issuer DN
);

CREATE TABLE IF NOT EXISTS certificate (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  issuer_ca_id BIGINT REFERENCES ca(id),
  log_url      VARCHAR(255) NOT NULL,
  log_index    BIGINT NOT NULL,
  sha256       VARCHAR(64) NOT NULL, -- hex SHA-256 от DER
  serial       VARCHAR(64),
  subject_dn   VARCHAR(1024),
  issuer_dn    VARCHAR(1024),
  not_before   TIMESTAMP,
  not_after    TIMESTAMP,
  entry_ts     TIMESTAMP, -- timestamp из MerkleTreeLeaf
  is_precert   BOOLEAN NOT NULL,
  der          BLOB NOT NULL,
  CONSTRAINT uq_log_entry UNIQUE (log_url, log_index)
);

CREATE TABLE IF NOT EXISTS cert_domain (
  cert_id         BIGINT NOT NULL REFERENCES certificate(id),
  -- CN + все dNSName из SAN в индексной форме (нижний регистр, расшифрованный юникод);
  -- почему именно такой — см. ru.crt.x509.DomainName
  domain          VARCHAR(255) NOT NULL,
  -- тот же домен задом наперёд: по нему поиск поддоменов (%.example.ru) становится
  -- поиском по префиксу и попадает в индекс, см. ru.crt.x509.DomainName
  domain_reversed VARCHAR(255) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_cert_domain ON cert_domain(domain);
CREATE INDEX IF NOT EXISTS idx_cert_domain_reversed ON cert_domain(domain_reversed);
-- индекса по cert_domain(cert_id) здесь нет намеренно: H2 сам создаёт его под внешний
-- ключ (CONSTRAINT_INDEX_*), и планировщик берёт именно его. Второе B-дерево по той же
-- колонке только удорожало бы вставку.
CREATE INDEX IF NOT EXISTS idx_cert_sha256 ON certificate(sha256);
