package cessda.cmv.benchmark.config;

import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import cessda.cmv.benchmark.tenant.TenantAuthFilter;

/**
 * Wires {@link TenantAuthFilter} into Spring Security's filter chain when
 * it is present.
 *
 * <p>
 * Spring Security does not automatically register {@code @Component}
 * beans that extend {@link org.springframework.web.filter.OncePerRequestFilter}
 * into its own chain — they must be added explicitly via
 * {@code addFilterBefore}. Without this configuration, TenantAuthFilter
 * exists as a bean but is never invoked for any request.
 *
 * <p>
 * {@link TenantAuthFilter} is itself {@code @ConditionalOnProperty}
 * gated on {@code tenants.enabled}, so it may not exist in the context
 * (e.g. in test profiles that don't enable tenancy). The dependency here
 * is therefore optional: when absent, requests simply pass through
 * Spring Security's default chain unauthenticated by TenantAuthFilter.
 *
 * <p>
 * CSRF is disabled because this is a stateless API authenticated via
 * the X-API-Key header rather than session cookies. All requests are
 * permitted past Spring Security's own authorisation layer because
 * TenantAuthFilter (when present) performs the actual authentication,
 * rejecting unrecognised keys with 401 before the request reaches any
 * controller.
 */
@Configuration
public class SecurityConfig {

    private final TenantAuthFilter tenantAuthFilter;

    public SecurityConfig(Optional<TenantAuthFilter> tenantAuthFilter) {
        this.tenantAuthFilter = tenantAuthFilter.orElse(null);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .httpBasic(AbstractHttpConfigurer::disable)      // disable HTTP Basic prompt
            .formLogin(AbstractHttpConfigurer::disable);     // disable form login

        if (tenantAuthFilter != null) {
            http.addFilterBefore(tenantAuthFilter, UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }
}