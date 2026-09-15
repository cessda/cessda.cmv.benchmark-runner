package eu.cessda.cmv.benchmark.web;

import eu.cessda.cmv.benchmark.config.BenchmarkProperties;
import eu.cessda.cmv.benchmark.tenant.TenantContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;

/**
 * Serves pre-generated manifest artefacts (summary.json, page-NNN.json)
 * scoped to the authenticated tenant.
 * <p>
 * The HTML dashboard fetches two URL patterns:
 * <ul>
 *   <li>{@code GET /api/results/summary.json}</li>
 *   <li>{@code GET /api/results/guids_{set}/pages/page-NNN.json}</li>
 * </ul>
 * <p>
 * Both are answered from the tenant's own results directory:
 * <ul>
 *   <li>{@code {resultsDir}/{tenantId}/summary.json}</li>
 *   <li>{@code {resultsDir}/{tenantId}/guids_{set}/pages/page-NNN.json}</li>
 * <p>
 * Authentication is handled upstream by TenantAuthFilter; by the time a
 * request reaches here, {@link TenantContext#getTenantId()} is guaranteed non-null.
 */
@RestController
@RequestMapping("/api/results")
public class DashboardController {

    private final BenchmarkProperties benchmarkProperties;
    private final TenantContext tenantContext;

    public DashboardController(BenchmarkProperties benchmarkProperties, TenantContext tenantContext) {
        this.benchmarkProperties = benchmarkProperties;
        this.tenantContext = tenantContext;
    }

    /**
     * GET /api/results/summary.json
     * Returns the tenant's pre-generated summary.json.
     */
    @GetMapping(value = "/summary.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public FileSystemResource getSummary() {
        Path file = tenantResultsDir().resolve("summary.json");
        return new FileSystemResource(file);
    }

    /**
     * GET /api/results/guids_{set}/pages/page-NNN.json
     * Returns a single paginated records file for the given set.
     *
     * The {set} path variable is validated to contain only word characters
     * and hyphens, preventing directory traversal.
     */
    @GetMapping(
        value = "/guids_{set}/pages/{page}",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<FileSystemResource> getPage(
            @PathVariable String set,
            @PathVariable String page) {

        // Validate inputs — set is e.g. "de", "sl-SI"; page is "page-001.json"
        if (!set.matches("[\\w\\-]+") || !page.matches("page-\\d{3}\\.json")) {
            return ResponseEntity.badRequest().build();
        }

        Path file = tenantResultsDir()
                .resolve("guids_" + set)
                .resolve("pages")
                .resolve(page)
                .normalize();

        // Ensure the resolved path is still inside the tenant's results dir —
        // belt-and-braces guard against any normalisation edge cases.
        Path tenantRoot = tenantResultsDir();
        if (!file.startsWith(tenantRoot)) {
            return ResponseEntity.status(403).build();
        }

        var fileResource = new FileSystemResource(file);

        return ResponseEntity.ok().body(fileResource);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private Path tenantResultsDir() {
        return benchmarkProperties.getResultsDir()
                .resolve(tenantContext.getTenantId())
                .normalize();
    }

}
