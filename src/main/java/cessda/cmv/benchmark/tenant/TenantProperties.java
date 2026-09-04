package cessda.cmv.benchmark.tenant;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
 *       oai-pmh-base-url: https://datacatalogue.cessda.eu/oai-pmh/v0/oai
 *     oxford:
 *       algorithm: https://docs.google.com/spreadsheets/d/OXFORD_SHEET_ID
 *       runner: https://tools.ostrails.eu/champion/assess/algorithm
 *       oai-pmh-base-url: https://oxford.example.org/oai-pmh/v0/oai
 * }</pre>
 *
 * <p>Each tenant has its own algorithm, runner, and OAI-PMH base URI,
 * since different organisations may use different FAIR Champion
 * configurations, runner instances, or source catalogues entirely.
 * {@code keys} and {@code config} are deliberately separate maps —
 * {@code keys} maps an API key to a tenant ID, while {@code config}
 * maps a tenant ID to that tenant's settings — so a tenant's secret
 * key is never used as a lookup key for its own configuration.</p>
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
         * This tenant's OAI-PMH base URL, used by the "Fetch identifiers"
         * dashboard page and the {@code /api/fetch-identifiers} endpoint
         * when no explicit {@code baseUrl} override is supplied for a
         * given run. Falls back to
         * {@link cessda.cmv.benchmark.GetOaiPmhIdentifiers#DEFAULT_OAI_PMH_BASE_URL}
         * if unset.
         */
        private String oaiPmhBaseUrl;

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

        private Map<String, String> setNames = new LinkedHashMap<>();
        private Map<String, String> fairMap  = new LinkedHashMap<>();
        private MaturityLevels maturityLevels = new MaturityLevels();

        @NotBlank
        private String title;

        @NotBlank
        private String footer;

        public String getAlgorithm() { return algorithm; }
        public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }

        public String getRunner() { return runner; }
        public void setRunner(String runner) { this.runner = runner; }

        public String getOaiPmhBaseUrl() { return oaiPmhBaseUrl; }
        public void setOaiPmhBaseUrl(String oaiPmhBaseUrl) { this.oaiPmhBaseUrl = oaiPmhBaseUrl; }

        public String getSpreadsheetUri() { return spreadsheetUri; }
        public void setSpreadsheetUri(String spreadsheetUri) { this.spreadsheetUri = spreadsheetUri; }

        public String getChampionUri() { return championUri; }
        public void setChampionUri(String championUri) { this.championUri = championUri; }

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