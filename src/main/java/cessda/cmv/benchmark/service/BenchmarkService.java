package cessda.cmv.benchmark.service;

import cessda.cmv.benchmark.GenerateManifest;
import cessda.cmv.benchmark.GetOaiPmhIdentifiers;
import cessda.cmv.benchmark.RunBenchmarkAssessment;
import cessda.cmv.benchmark.config.BenchmarkProperties;
import cessda.cmv.benchmark.tenant.TenantContext;
import cessda.cmv.benchmark.tenant.TenantProperties;
import cessda.cmv.benchmark.tenant.TenantProperties.TenantConfig;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class BenchmarkService {

    private final BenchmarkProperties benchmarkProperties;
    private final TenantContext tenantContext;
    private final TenantProperties tenantProperties;

    public record Branding(String title, String footer) {}

    public BenchmarkService(BenchmarkProperties benchmarkProperties,
                             TenantContext tenantContext,
                             TenantProperties tenantProperties) {
        this.benchmarkProperties = benchmarkProperties;
        this.tenantContext    = tenantContext;
        this.tenantProperties = tenantProperties;
    }

    /**
    * Returns the title and footer for the current tenant.
    *
    * @return a {@link Branding} record containing the title and footer strings
    */
    public Branding getTenantBranding() {
       TenantConfig cfg = currentTenantConfig();
       return new Branding(cfg.getTitle(), cfg.getFooter());
    }

    /**
     * Returns the algorithm and runner URIs that would be used for
     * the current tenant if {@link #runAssessment} were called with
     * no explicit overrides.
     *
     * <p>Used to populate the "Run assessment" confirmation dialog in
     * the dashboard, so the operator can see and optionally override
     * the values that will actually be sent before triggering a
     * potentially long-running assessment run.</p>
     *
     * @return a two-element array: {@code [algorithm, runner]}.
     */
    public URI[] getDefaultAlgorithmAndRunner() {
        return new URI[]{resolveAlgorithm(null), resolveRunner(null)};
    }

    /**
     * Lists the {@code guids_*.txt} files currently present in the
     * current tenant's data directory, sorted alphabetically.
     *
     * <p>Used to populate the "Run assessment" confirmation dialog
     * with a checkable list of available sets, so the operator can
     * choose to assess only some of them rather than always running
     * every default set via {@link #runAssessment}'s
     * {@code processAll} flag.</p>
     *
     * @return filenames only (e.g. {@code "guids_de.txt"}), not full
     *         paths; an empty list if the tenant's data directory does
     *         not exist or contains no matching files
     * @throws IOException if the directory cannot be read
     */
    public java.util.List<String> listGuidFiles() throws IOException {
        Path tDataDir = tenantDataDir();
        if (!Files.isDirectory(tDataDir)) {
            return java.util.List.of();
        }
        try (var stream = Files.list(tDataDir)) {
            return stream
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("guids_") && name.endsWith(".txt"))
                    .sorted()
                    .toList();
        }
    }

    // ── Path helpers ─────────────────────────────────────────────────────────

    /** /data/{tenantId}/ */
    private Path tenantDataDir() {
        return benchmarkProperties.getDataDir()
                .resolve(tenantContext.getTenantId())
                .normalize();
    }

    /** /results/{tenantId}/ */
    private Path tenantResultsDir() {
        return benchmarkProperties.getResultsDir()
                .resolve(tenantContext.getTenantId())
                .normalize();
    }

    /**
     * Returns the {@link TenantConfig} for the current tenant, or
     * {@code null} if no {@code tenants.config} entry exists for it.
     * Used by the branding endpoint to expose title and footer values.
     */
    public TenantConfig getCurrentTenantConfig() {
        return currentTenantConfig();
    }

    // ── Per-tenant configuration helpers ─────────────────────────────────────

    /**
     * Resolves the FAIR Champion algorithm URI for the current tenant.
     *
     * <p>Uses the explicit request override when supplied; otherwise
     * resolves the current tenant's configured
     * {@code tenants.config.<tenantId>.algorithm} value.</p>
     */
    private URI resolveAlgorithm(URI requestedOverride) {
        if (requestedOverride != null) {
            return requestedOverride;
        }
        URI tenantValue = currentTenantConfig().effectiveAlgorithm();
        if (tenantValue != null) {
            return tenantValue;
        }
        URI sharedValue = benchmarkProperties.getAlgorithm();
        if (sharedValue != null) {
            return sharedValue;
        }
        throw new IllegalStateException(
                "No algorithm configured for tenant: " + tenantContext.getTenantId());
    }

    /**
     * Resolves the FAIR Champion runner URI for the current tenant.
     *
     * <p>Uses the explicit request override when supplied; otherwise
     * resolves the current tenant's configured
     * {@code tenants.config.<tenantId>.runner} value.</p>
     */
    private URI resolveRunner(URI requestedOverride) {
        if (requestedOverride != null) {
            return requestedOverride;
        }
        URI tenantValue = currentTenantConfig().effectiveRunner();
        if (tenantValue != null) {
            return tenantValue;
        }
        URI sharedValue = benchmarkProperties.getRunner();
        if (sharedValue != null) {
            return sharedValue;
        }
        throw new IllegalStateException("No runner configured for tenant: " + tenantContext.getTenantId());
    }

    // ── 1. Fetch OAI-PMH Identifiers ─────────────────────────────────────────

    public String fetchIdentifiers(
            String baseUrl,
            String verb,
            String metadataPrefix,
            String sets,
            String fetchSet) throws IOException, InterruptedException {

        Path tDataDir = tenantDataDir();
        Files.createDirectories(tDataDir);

        String resolvedBase   = nvl(baseUrl,       GetOaiPmhIdentifiers.DEFAULT_OAI_PMH_BASE_URL);
        String resolvedVerb   = nvl(verb,           GetOaiPmhIdentifiers.DEFAULT_VERB);
        String resolvedPrefix = nvl(metadataPrefix, GetOaiPmhIdentifiers.DEFAULT_METADATA_PREFIX);

        GetOaiPmhIdentifiers client =
                new GetOaiPmhIdentifiers(resolvedBase, resolvedVerb, resolvedPrefix, tDataDir);

        if (fetchSet != null && !fetchSet.isBlank()) {
            client.fetchIdentifiersForSet(fetchSet.trim());
            return "Fetched identifiers for set: " + fetchSet.trim()
                    + " -> " + tDataDir + "/guids_" + fetchSet.trim() + ".txt";
        }

        String[] resolvedSets = (sets != null && !sets.isBlank())
                ? sets.split(",")
                : GetOaiPmhIdentifiers.DEFAULT_SETS;

        client.fetchAllSetIdentifiers(resolvedSets);
        return "Fetched identifiers for " + resolvedSets.length + " set(s) -> " + tDataDir;
    }

    // ── 2. Run Assessment ────────────────────────────────────────────────────

    public String runAssessment(
            URI spreadsheetUri,
            URI runnerUri,
            String guidFile,
            java.util.List<String> guidFiles,
            String guid,
            boolean processAll) throws IOException, InterruptedException {

        Path tDataDir    = tenantDataDir();
        Path tResultsDir = tenantResultsDir();
        Files.createDirectories(tDataDir);
        Files.createDirectories(tResultsDir);

        URI resolvedAlgorithm = resolveAlgorithm(spreadsheetUri);
        URI resolvedRunner = resolveRunner(runnerUri);

        // Pass tenant-scoped dirs and tenant-scoped algorithm/runner
        // explicitly — no system property side-effects, and no two
        // tenants ever share the same Champion configuration unless
        // their application.yml entries say so deliberately.
        RunBenchmarkAssessment runner =
                new RunBenchmarkAssessment(resolvedAlgorithm, resolvedRunner,
                                           tDataDir, tResultsDir);

        if (guid != null && !guid.isBlank()) {
            runner.processSingleGuid(guid.trim());
            return "Processed single GUID: " + guid.trim()
                    + " -> " + tResultsDir;
        }

        if (guidFiles != null && !guidFiles.isEmpty()) {
            int processed = 0;
            java.util.List<String> skipped = new java.util.ArrayList<>();
            for (String filename : guidFiles) {
                if (filename == null || filename.isBlank()) continue;
                try {
                    Path resolved = resolveGuidFile(filename.trim(), tDataDir);
                    runner.processSingleFile(resolved);
                    processed++;
                } catch (java.io.FileNotFoundException fnfe) {
                    skipped.add(filename.trim());
                }
            }
            String message = "Processed " + processed + " selected set file(s) -> " + tResultsDir;
            if (!skipped.isEmpty()) {
                message += " (skipped, not found: " + String.join(", ", skipped) + ")";
            }
            return message;
        }

        if (guidFile != null && !guidFile.isBlank()) {
            Path resolved = resolveGuidFile(guidFile.trim(), tDataDir);
            runner.processSingleFile(resolved);
            return "Processed file: " + resolved + " -> " + tResultsDir;
        }

        if (processAll) {
            runner.processAllSetFiles();
            return "Processed all set files from " + tDataDir
                    + " -> " + tResultsDir;
        }

        Path defaultFile = resolveGuidFile(RunBenchmarkAssessment.DEFAULT_GUIDS_FILE, tDataDir);
        runner.processSingleFile(defaultFile);
        return "Processed default file: " + defaultFile + " -> " + tResultsDir;
    }

    // ── 3. Generate Manifest ─────────────────────────────────────────────────

    public String generateManifest(String overrideResultsDir) throws IOException {
        TenantConfig cfg = currentTenantConfig();
        Path targetDir = (overrideResultsDir != null && !overrideResultsDir.isBlank())
                ? Path.of(overrideResultsDir).toAbsolutePath().normalize()
                : tenantResultsDir();   // <-- tenant-scoped by default

        if (!Files.isDirectory(targetDir)) {
            throw new IOException("Results directory not found: " + targetDir);
        }

        new GenerateManifest(
                targetDir,
                cfg.getFairMap(),
                cfg.getMaturityLevels().getLevel1(),
                cfg.getMaturityLevels().getLevel2(),
                cfg.getMaturityLevels().getLevel3())
            .run();
        return "Manifest generated in: " + targetDir;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private Path resolveGuidFile(String filename, Path tDataDir) {
        Path asGiven = Path.of(filename);
        if (Files.exists(asGiven)) return asGiven;
        return tDataDir.resolve(asGiven.getFileName());
    }

    private static String nvl(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    private TenantConfig currentTenantConfig() {
        String tenantId = tenantContext.getTenantId();
        TenantConfig cfg = tenantProperties.getConfigFor(tenantId);
        if (cfg == null) {
            throw new IllegalStateException(
                    "No tenant configuration found for tenant: " + tenantId);
        }
        return cfg;
    }
}