package cessda.cmv.benchmark.tenant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
@ConfigurationProperties(prefix = "tenants")
@Validated
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

        private URI algorithm;

        private URI runner;

        /**
         * Legacy alias for {@link #algorithm}. Kept for compatibility with
         * older tenant configuration keys.
         */
        private URI spreadsheetUri;

        /**
         * Legacy alias for {@link #runner}. Kept for compatibility with older
         * tenant configuration keys.
         */
        private URI championUri;

        private Map<String, String> setNames = new LinkedHashMap<>();
        private Map<String, String> fairMap  = new LinkedHashMap<>();
        private MaturityLevels maturityLevels = new MaturityLevels();

        @NotBlank
        private String title;

        @NotBlank
        private String footer;

        public URI getAlgorithm() {
            return algorithm;
        }

        public void setAlgorithm(URI algorithm) {
            this.algorithm = algorithm;
        }

        public URI getRunner() {
            return runner;
        }

        public void setRunner(URI runner) {
            this.runner = runner;
        }

        public URI getSpreadsheetUri() {
            return spreadsheetUri;
        }

        public void setSpreadsheetUri(URI spreadsheetUri) {
            this.spreadsheetUri = spreadsheetUri;
        }

        public URI getChampionUri() {
            return championUri;
        }

        public void setChampionUri(URI championUri) {
            this.championUri = championUri;
        }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getFooter() { return footer; }
        public void setFooter(String footer) { this.footer = footer; }

        public Map<String, String> getSetNames() { return setNames; }
        public void setSetNames(Map<String, String> setNames) { this.setNames = setNames; }

        public Map<String, String> getFairMap() { return fairMap; }
        public void setFairMap(Map<String, String> fairMap) {
            this.fairMap = fairMap != null ? fairMap : new LinkedHashMap<>();
        }

        public MaturityLevels getMaturityLevels() { return maturityLevels; }
        public void setMaturityLevels(MaturityLevels maturityLevels) {
            this.maturityLevels = maturityLevels != null
                ? maturityLevels
                : new MaturityLevels();
        }

        public URI effectiveAlgorithm() {
            if (algorithm != null) {
                return algorithm;
            }
            return spreadsheetUri;
        }

        public URI effectiveRunner() {
            if (runner != null) {
                return runner;
            }
            return championUri;
        }

        public static class MaturityLevels {

            private List<String> level1 = List.of();
            private List<String> level2 = List.of();
            private List<String> level3 = List.of();

            public List<String> getLevel1() { return level1; }
            public void setLevel1(List<String> level1) {
                this.level1 = level1 != null ? level1 : List.of();
            }

            public List<String> getLevel2() { return level2; }
            public void setLevel2(List<String> level2) {
                this.level2 = level2 != null ? level2 : List.of();
            }

            public List<String> getLevel3() { return level3; }
            public void setLevel3(List<String> level3) {
                this.level3 = level3 != null ? level3 : List.of();
            }
        }
    }
}
