package ru.crt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки {@code ct.*}. Привязываются JavaBeanBinder'ом, поэтому у каждого ключа,
 * который вообще встречается в конфигурации, должен быть сеттер, а поле не может быть
 * final: без сеттера Spring падает на старте с «No setter found for property» — и роняет
 * привязку всего {@code ct.*}, а не одного ключа. Вложенные объекты ({@link RateLimit},
 * {@link Auth}) создаются здесь и привязываются по своим сеттерам, поэтому их поля final.
 */
@ConfigurationProperties(prefix = "ct")
public class AppProperties {

    /** URL log_list_v3 (ctlog.json) со списком российских CT-логов. */
    private String logListUrl = "https://browser-resources.s3.yandex.net/ctlog/ctlog.json";

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

        /**
         * true => тем же токеном закрыт весь сайт, кроме /api/status (почему — см.
         * {@code AuthTokenFilter.shouldNotFilter}). Так живёт стенд.
         */
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
