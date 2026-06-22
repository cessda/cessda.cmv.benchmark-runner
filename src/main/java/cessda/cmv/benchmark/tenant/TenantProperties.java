package cessda.cmv.benchmark.tenant;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
 *       spreadsheetUri: https://docs.google.com/spreadsheets/d/CESSDA_SHEET_ID
 *       championUri: https://tools.ostrails.eu/champion/assess/spreadsheetUri
 *     oxford:
 *       spreadsheetUri: https://docs.google.com/spreadsheets/d/OXFORD_SHEET_ID
 *       championUri: https://tools.ostrails.eu/champion/assess/spreadsheetUri
 * }</pre>
 *
 * <p>Each tenant has its own spreadsheetUri and championUri URI, since different
 * organisations may use different FAIR Champion configurations or
 * championUri instances. {@code keys} and {@code config} are deliberately
 * separate maps — {@code keys} maps an API key to a tenant ID, while
 * {@code config} maps a tenant ID to that tenant's settings — so a
 * tenant's secret key is never used as a lookup key for its own
 * configuration.</p>
 */
@Component
@ConfigurationProperties(prefix = "tenants")
public class TenantProperties {

    /** API key -> tenantId */
    private Map<String, String> keys = new HashMap<>();

    /** tenantId -> per-tenant benchmark configuration */
    private Map<String, TenantConfig> config = new HashMap<>();

    public Map<String, String> getKeys() { return keys; }
    public void setKeys(Map<String, String> keys) { this.keys = keys; }

    public Map<String, TenantConfig> getConfig() { return config; }
    public void setConfig(Map<String, TenantConfig> config) { this.config = config; }

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
     * Per-tenant benchmark settings: the FAIR Champion spreadsheetUri and
     * championUri URIs to use for that tenant's assessments.
     */
    public static class TenantConfig {

        private String spreadsheetUri;
        private String championUri;

        public String getSpreadsheetUri() { return spreadsheetUri; }
        public void setSpreadsheetUri(String spreadsheetUri) { this.spreadsheetUri = spreadsheetUri; }

        public String getChampionUri() { return championUri; }
        public void setChampionUri(String championUri) { this.championUri = championUri; }
    }
}