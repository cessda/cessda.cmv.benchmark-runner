package cessda.cmv.benchmark.tenant;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Holds the resolved tenant ID for the lifetime of a single HTTP request.
 * Injected into any service that needs to scope file I/O to a tenant.
 */
@Component
@RequestScope
public class TenantContext {

    private String tenantId;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }
}
