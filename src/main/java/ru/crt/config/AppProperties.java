package ru.crt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки {@code ct.*}: JavaBeanBinder требует сеттер у каждого ключа,
 * поэтому поля не final (вложенные объекты создаются здесь — их поля final).
 */
@ConfigurationProperties(prefix = "ct")
public class AppProperties {

    /** URL log_list_v3 (ctlog.json) со списком российских CT-логов. */
    private String logListUrl = "https://browser-resources.s3.yandex.net/ctlog/ctlog.json";

    private int batchSize = 256;

    /** PEM с дополнительными доверенными корнями (например, Минцифры); пусто — только cacerts. */
    private String trustCert = "";

    private final RateLimit rateLimit = new RateLimit();
    private final Auth auth = new Auth();

    public String getLogListUrl() {
        return logListUrl;
    }

    public void setLogListUrl(String logListUrl) {
        this.logListUrl = logListUrl;
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

        /** Пусто => закрытые эндпоинты всегда отвечают 401. Переопределяется CT_AUTH_TOKEN. */
        private String token = "";

        /** Тем же токеном закрыт весь сайт, кроме /api/status. Так живёт стенд. */
        private boolean protectAll = false;

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public boolean isProtectAll() {
            return protectAll;
        }

        public void setProtectAll(boolean protectAll) {
            this.protectAll = protectAll;
        }
    }
}
