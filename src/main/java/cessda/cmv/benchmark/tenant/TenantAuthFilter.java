package cessda.cmv.benchmark.tenant;

import java.io.IOException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Reads the X-API-Key header, resolves it to a tenant ID, and stores it in
 * the request-scoped TenantContext.  Returns 401 for missing or unknown keys.
 *
 * <p>Public paths (actuator health, static dashboard assets) are exempted.
 * The HTML dashboard itself is served without authentication — only the
 * underlying {@code /api/**} data endpoints it calls via {@code fetch()}
 * require a valid API key.</p>
 */
@Component
@ConditionalOnProperty(name = "tenants.enabled", havingValue = "true", matchIfMissing = false)
public class TenantAuthFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";

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

        // Exempt health checks, the static dashboard HTML/assets, and
        // the SpringDoc/Swagger UI paths so the API documentation is
        // accessible without an API key.
        //
        // Spring Boot's WelcomePageHandlerMapping internally forwards
        // "GET /" to "/index.html" before this filter evaluates the
        // request, so the servlet path seen here is "/index.html", not
        // "/" — both must be exempted explicitly. Only the /api/**
        // endpoints the dashboard JavaScript calls via fetch() require
        // an API key; the HTML shell and its static assets do not.
        return path.startsWith("/actuator")
            || path.startsWith("/static")
            || path.equals("/")
            || path.equals("/index.html")
            || path.equals("/detail.html")
            || path.startsWith("/css/")
            || path.startsWith("/js/")
            || path.equals("/favicon.ico")
            || path.startsWith("/api-docs");
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
        chain.doFilter(request, response);
    }
}