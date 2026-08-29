package ru.crt.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ct")
public class AppProperties {

    /** URL log_list_v3 (ctlog.json) со списком российских CT-логов. */
    private String logListUrl = "https://browser-resources.s3.yandex.net/ctlog/ctlog.json";

    /** Период между проходами синхронизации. */
    private Duration syncInterval = Duration.ofMinutes(10);

    /** Сколько записей запрашивать одним get-entries. */
    private int batchSize = 256;

    /**
     * PEM-файл с дополнительными доверенными сертификатами (например, корень Минцифры)
     * для HTTPS-клиента CT-логов. JDK не модифицируется: стандартный cacerts
     * остаётся доверенным, файл лишь добавляет якоря. Пусто — только cacerts.
     */
    private String trustCert = "";

    private final RateLimit rateLimit = new RateLimit();
    private final Auth auth = new Auth();

    public String getLogListUrl() {
        return logListUrl;
    }

    public void setLogListUrl(String logListUrl) {
        this.logListUrl = logListUrl;
    }

    public Duration getSyncInterval() {
        return syncInterval;
    }

    public void setSyncInterval(Duration syncInterval) {
        this.syncInterval = syncInterval;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public String getTrustCert() {
        return trustCert;
    }

    public void setTrustCert(String trustCert) {
        this.trustCert = trustCert;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Auth getAuth() {
        return auth;
    }

    public static class RateLimit {

        private boolean enabled = true;
        private double rps = 1.0;
        private int burst = 3;
        private boolean trustForwarded = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getRps() {
            return rps;
        }

        public void setRps(double rps) {
            this.rps = rps;
        }

        public int getBurst() {
            return burst;
        }

        public void setBurst(int burst) {
            this.burst = burst;
        }

        public boolean isTrustForwarded() {
            return trustForwarded;
        }

        public void setTrustForwarded(boolean trustForwarded) {
            this.trustForwarded = trustForwarded;
        }
    }

    public static class Auth {

        /** Пусто => /api/logs всегда отвечает 401. Переопределяется CT_AUTH_TOKEN. */
        private String token = "";

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }
}
