package cessda.cmv.benchmark.tenant;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Maps API keys to tenant IDs, loaded from application.yml.
 *
 * Example application.yml:
 *
 *   tenants:
 *     keys:
 *       "key-abc123": "org-cessda"
 *       "key-def456": "org-ukds"
 */
@Component
@ConfigurationProperties(prefix = "tenants")
public class TenantProperties {

    /** API key -> tenantId */
    private Map<String, String> keys = new HashMap<>();

    public Map<String, String> getKeys() { return keys; }
    public void setKeys(Map<String, String> keys) { this.keys = keys; }

    /** @return the tenantId for the given API key, or null if unrecognised */
    public String resolve(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return null;
        return keys.get(apiKey.trim());
    }
}