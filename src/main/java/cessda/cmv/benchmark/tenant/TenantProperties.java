package cessda.cmv.benchmark.tenant;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Maps API keys to tenant IDs, and tenant IDs to their per-tenant
 * benchmark configuration, loaded from application.yml.
 *
 * <p>Example application.yml:</p>
 *
 * <pre>{@code
 * tenants:
 *   enabled: true
 *   keys:
 *     "key-cessda": "cessda"
 *     "key-oxford": "oxford"
 *   config:
 *     cessda:
 *       algorithm: https://docs.google.com/spreadsheets/d/CESSDA_SHEET_ID
 *       runner: https://tools.ostrails.eu/champion/assess/algorithm
 *     oxford:
 *       algorithm: https://docs.google.com/spreadsheets/d/OXFORD_SHEET_ID
 *       runner: https://tools.ostrails.eu/champion/assess/algorithm
 * }</pre>
 *
 * <p>Each tenant has its own algorithm and runner URI, since different
 * organisations may use different FAIR Champion configurations or
 * runner instances. {@code keys} and {@code config} are deliberately
 * separate maps — {@code keys} maps an API key to a tenant ID, while
 * {@code config} maps a tenant ID to that tenant's settings — so a
 * tenant's secret key is never used as a lookup key for its own
 * configuration.</p>
 */
@Component
@Validated
@ConfigurationProperties(prefix = "tenants")
public class TenantProperties {

    /** API key -> tenantId */
    private Map<String, String> keys = new HashMap<>();

    /** tenantId -> per-tenant benchmark configuration */
    private Map<String, @Valid TenantConfig> config = new HashMap<>();

    public Map<String, String> getKeys() { return keys; }
    public void setKeys(Map<String, String> keys) {
        this.keys = keys != null ? keys : new HashMap<>();
    }

    public Map<String, TenantConfig> getConfig() { return config; }
    public void setConfig(Map<String, TenantConfig> config) {
        this.config = config != null ? config : new HashMap<>();
    }

    /** @return the tenantId for the given API key, or null if unrecognised */
    public String resolve(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return null;
        return keys.get(apiKey.trim());
    }

    /**
     * Returns the benchmark configuration for the given tenant ID.
     *
     * @param tenantId the tenant ID (not the API key)
     * @return the tenant's configuration, or {@code null} if no
     *         {@code tenants.config} entry exists for that tenant ID
     */
    public TenantConfig getConfigFor(String tenantId) {
        if (tenantId == null) return null;
        return config.get(tenantId);
    }

    /**
     * Per-tenant benchmark settings: the FAIR Champion algorithm and
     * runner URIs, and the branding strings shown in the dashboard UI.
     */
    public static class TenantConfig {

        private String algorithm;

        private String runner;

        /**
         * Legacy alias for {@link #algorithm}. Kept for compatibility with
         * older tenant configuration keys.
         */
        private String spreadsheetUri;

        /**
         * Legacy alias for {@link #runner}. Kept for compatibility with older
         * tenant configuration keys.
         */
        private String championUri;

        @NotBlank
        private String title;

        @NotBlank
        private String footer;

        private Map<String, String> setNames = new HashMap<>();

        private Map<String, String> fairMap = new HashMap<>();

        public String getAlgorithm() { return algorithm; }
        public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }

        public String getRunner() { return runner; }
        public void setRunner(String runner) { this.runner = runner; }

        public String getSpreadsheetUri() { return spreadsheetUri; }
        public void setSpreadsheetUri(String spreadsheetUri) { this.spreadsheetUri = spreadsheetUri; }

        public String getChampionUri() { return championUri; }
        public void setChampionUri(String championUri) { this.championUri = championUri; }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getFooter() { return footer; }
        public void setFooter(String footer) { this.footer = footer; }

        public Map<String, String> getSetNames() { return setNames; }
        public void setSetNames(Map<String, String> setNames) {
            this.setNames = setNames != null ? setNames : new HashMap<>();
        }

        public Map<String, String> getFairMap() { return fairMap; }
        public void setFairMap(Map<String, String> fairMap) {
            this.fairMap = fairMap != null ? fairMap : new HashMap<>();
        }

        public String effectiveAlgorithm() {
            if (algorithm != null && !algorithm.isBlank()) {
                return algorithm;
            }
            return spreadsheetUri;
        }

        public String effectiveRunner() {
            if (runner != null && !runner.isBlank()) {
                return runner;
            }
            return championUri;
        }
    }
}
