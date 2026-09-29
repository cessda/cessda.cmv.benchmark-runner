package eu.cessda.cmv.benchmark.tenant;

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
         * This tenant's OAI-PMH base URL, used by the "Fetch identifiers"
         * dashboard page and the {@code /api/fetch-identifiers} endpoint
         * when no explicit {@code baseUrl} override is supplied for a
         * given run. Falls back to
         * {@link eu.cessda.cmv.benchmark.GetOaiPmhIdentifiers#DEFAULT_OAI_PMH_BASE_URL}
         * if unset.
         */
        private URI oaiPmhBaseUrl;

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

        public URI getOaiPmhBaseUrl() {
            return oaiPmhBaseUrl;
        }

        public void setOaiPmhBaseUrl(URI oaiPmhBaseUrl) {
            this.oaiPmhBaseUrl = oaiPmhBaseUrl;
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

        /**
         * How a record's maturity level (0-3) is derived from its test
         * results. Two mutually exclusive models are supported, selected
         * by {@link #method}:
         *
         * <ul>
         *   <li>{@link Method#CHECKLIST} (default) — a record reaches a
         *       level only if it passed <em>every</em> test named in that
         *       level's list ({@link #level1}/{@link #level2}/
         *       {@link #level3}). Lists are cumulative by convention
         *       (level2 should list a superset of level1's tests, etc.),
         *       though this is not enforced. This is CESSDA's model and
         *       the only one that existed before weighted-score support
         *       was added — existing {@code tenants.config} entries that
         *       set only {@code level1}/{@code level2}/{@code level3}
         *       need no changes at all.</li>
         *   <li>{@link Method#WEIGHTED_SCORE} — each FAIR category (F, A,
         *       I, R) contributes a percentage, {@code (sum of that
         *       category's earned test weights) / categoryMax(category)
         *       * 100}; a record's overall score is the average of the
         *       categories present in {@link #categoryMax}. A level is
         *       reached once that score meets its threshold
         *       ({@link #level1Threshold}/{@link #level2Threshold}/
         *       {@link #level3Threshold}). This is Oxford's model,
         *       matching the weighted percentage formulas in their
         *       Benchmark Assessment Algorithm spreadsheet.</li>
         * </ul>
         */
        public static class MaturityLevels {

            public enum Method { CHECKLIST, WEIGHTED_SCORE }

            private Method method = Method.CHECKLIST;

            // ── CHECKLIST fields ────────────────────────────────────────
            private List<String> level1 = List.of();
            private List<String> level2 = List.of();
            private List<String> level3 = List.of();

            // ── WEIGHTED_SCORE fields ───────────────────────────────────
            /** FAIR category letter (F/A/I/R) -> that category's maximum achievable weight. */
            private Map<String, Double> categoryMax = new LinkedHashMap<>();
            private Double level1Threshold;
            private Double level2Threshold;
            private Double level3Threshold;

            public Method getMethod() { return method; }
            public void setMethod(Method method) {
                this.method = method != null ? method : Method.CHECKLIST;
            }

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

            public Map<String, Double> getCategoryMax() { return categoryMax; }
            public void setCategoryMax(Map<String, Double> categoryMax) {
                this.categoryMax = categoryMax != null ? categoryMax : new LinkedHashMap<>();
            }

            public Double getLevel1Threshold() { return level1Threshold; }
            public void setLevel1Threshold(Double level1Threshold) {
                this.level1Threshold = level1Threshold;
            }

            public Double getLevel2Threshold() { return level2Threshold; }
            public void setLevel2Threshold(Double level2Threshold) {
                this.level2Threshold = level2Threshold;
            }

            public Double getLevel3Threshold() { return level3Threshold; }
            public void setLevel3Threshold(Double level3Threshold) {
                this.level3Threshold = level3Threshold;
            }
        }
    }
}