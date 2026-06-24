package cessda.cmv.benchmark.web;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cessda.cmv.benchmark.config.BenchmarkProperties;
import cessda.cmv.benchmark.tenant.TenantContext;

/**
 * Serves pre-generated manifest artefacts (summary.json, page-NNN.json)
 * scoped to the authenticated tenant.
 *
 * The HTML dashboard fetches two URL patterns:
 *   GET /api/results/summary.json
 *   GET /api/results/guids_{lang}/pages/page-NNN.json
 *
 * Both are answered from the tenant's own results directory:
 *   {resultsDir}/{tenantId}/summary.json
 *   {resultsDir}/{tenantId}/guids_{lang}/pages/page-NNN.json
 *
 * Authentication is handled upstream by TenantAuthFilter; by the time a
 * request reaches here, TenantContext.getTenantId() is guaranteed non-null.
 */
@RestController
@RequestMapping("/api/results")
public class DashboardController {

    private final BenchmarkProperties benchmarkProperties;
    private final TenantContext tenantContext;

    public DashboardController(BenchmarkProperties benchmarkProperties,
                               TenantContext tenantContext) {
        this.benchmarkProperties = benchmarkProperties;
        this.tenantContext = tenantContext;
    }

    /**
     * GET /api/results/summary.json
     * Returns the tenant's pre-generated summary.json.
     */
    @GetMapping(value = "/summary.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> getSummary() {
        Path file = tenantResultsDir().resolve("summary.json");
        return serveFile(file);
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
    public ResponseEntity<Resource> getPage(
            @PathVariable String set,
            @PathVariable String page) {

        // Validate inputs — set is e.g. "de", "sl-SI"; page is "page-001.json"
        if (!set.matches("[\\w\\-]+") || !page.matches("page-\\d{3}\\.json")) {
            return ResponseEntity.badRequest().build();
        }

        Path file = tenantResultsDir()
                .resolve("guids_" + set)
                .resolve("pages")
                .resolve(page);

        return serveFile(file);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private Path tenantResultsDir() {
        return benchmarkProperties.getResultsDirPath()
                .resolve(tenantContext.getTenantId())
                .normalize();
    }

    private ResponseEntity<Resource> serveFile(Path file) {
        // Ensure the resolved path is still inside the tenant's results dir —
        // belt-and-braces guard against any normalisation edge cases.
        Path tenantRoot = tenantResultsDir();
        if (!file.startsWith(tenantRoot)) {
            return ResponseEntity.status(403).build();
        }

        if (!Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(new FileSystemResource(file));
    }
}
