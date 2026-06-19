package cessda.cmv.benchmark.tenant;

import java.io.IOException;
import java.util.logging.Logger;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import cessda.cmv.benchmark.GenerateManifest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Reads the X-API-Key header, resolves it to a tenant ID, and stores it in
 * the request-scoped TenantContext.  Returns 401 for missing or unknown keys.
 *
 * Public paths (actuator health, static assets) are exempted.
 */
@Component
@ConditionalOnProperty(name = "tenants.enabled", havingValue = "true", matchIfMissing = false)
public class TenantAuthFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final Logger log = Logger.getLogger(GenerateManifest.class.getName());


    private final TenantProperties tenantProperties;
    private final TenantContext tenantContext;

    public TenantAuthFilter(TenantProperties tenantProperties,
                            TenantContext tenantContext) {
        this.tenantProperties = tenantProperties;
        this.tenantContext    = tenantContext;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        // Exempt health checks and static dashboard assets
        return path.startsWith("/actuator")
            || path.startsWith("/static")
            || path.equals("/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String apiKey  = request.getHeader(API_KEY_HEADER);
        String tenantId = tenantProperties.resolve(apiKey);

        if (tenantId == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter()
                    .write("{\"error\":\"Missing or invalid API key\"}");
            return;
        }

        tenantContext.setTenantId(tenantId);
        log.info("Authenticated request for tenant: " + tenantId);
        chain.doFilter(request, response);
    }
}