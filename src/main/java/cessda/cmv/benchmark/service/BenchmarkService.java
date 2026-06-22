package cessda.cmv.benchmark.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import cessda.cmv.benchmark.GenerateManifest;
import cessda.cmv.benchmark.GetOaiPmhIdentifiers;
import cessda.cmv.benchmark.RunBenchmarkAssessment;
import cessda.cmv.benchmark.tenant.TenantContext;
import cessda.cmv.benchmark.tenant.TenantProperties;
import cessda.cmv.benchmark.tenant.TenantProperties.TenantConfig;

@Service
public class BenchmarkService {

    @Value("${benchmark.data-dir:/data}")
    private String dataDir;

    @Value("${benchmark.results-dir:/results}")
    private String resultsDir;

    // Fallback values used only when a tenant has no entry under
    // tenants.config — keeps existing single-tenant deployments working
    // without requiring every tenant to be migrated to the new config
    // map immediately.
    @Value("${benchmark.runner:}")
    private String defaultBenchmarkRunner;

    @Value("${benchmark.algorithm:}")
    private String defaultBenchmarkAlgorithm;

    private final TenantContext tenantContext;
    private final TenantProperties tenantProperties;

    public BenchmarkService(TenantContext tenantContext,
                             TenantProperties tenantProperties) {
        this.tenantContext    = tenantContext;
        this.tenantProperties = tenantProperties;
    }

    /**
     * Returns the algorithm and runner URIs that would be used for
     * the current tenant if {@link #runAssessment} were called with
     * no explicit overrides — i.e. the tenant's {@code tenants.config}
     * entry, falling back to the shared {@code benchmark.algorithm} /
     * {@code benchmark.runner} properties.
     *
     * <p>Used to populate the "Run assessment" confirmation dialog in
     * the dashboard, so the operator can see and optionally override
     * the values that will actually be sent before triggering a
     * potentially long-running assessment run.</p>
     *
     * @return a two-element array: {@code [algorithm, runner]}. Either
     *         element may be {@code null} or blank if no default is
     *         configured for this tenant and no shared fallback is set.
     */
    public String[] getDefaultAlgorithmAndRunner() {
        return new String[] { resolveAlgorithm(null), resolveRunner(null) };
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
        return Paths.get(dataDir, tenantContext.getTenantId()).toAbsolutePath().normalize();
    }

    /** /results/{tenantId}/ */
    private Path tenantResultsDir() {
        return Paths.get(resultsDir, tenantContext.getTenantId()).toAbsolutePath().normalize();
    }

    // ── Per-tenant configuration helpers ─────────────────────────────────────

    /**
     * Resolves the FAIR Champion algorithm URI for the current tenant.
     *
     * <p>Looks up {@code tenants.config.<tenantId>.algorithm} first;
     * falls back to the shared {@code benchmark.algorithm} property
     * (if set) for tenants that have not been migrated to per-tenant
     * configuration, then to the explicit {@code spreadsheetUri}
     * parameter if the caller supplied one.</p>
     */
    private String resolveAlgorithm(String requestedOverride) {
        if (requestedOverride != null && !requestedOverride.isBlank()) {
            return requestedOverride;
        }
        TenantConfig cfg = tenantProperties.getConfigFor(tenantContext.getTenantId());
        if (cfg != null && cfg.getSpreadsheetUri() != null && !cfg.getSpreadsheetUri().isBlank()) {
            return cfg.getSpreadsheetUri();
        }
        return defaultBenchmarkAlgorithm;
    }

    /**
     * Resolves the FAIR Champion runner URI for the current tenant.
     *
     * <p>Uses the explicit {@code requestedOverride} if supplied;
     * otherwise looks up {@code tenants.config.<tenantId>.runner};
     * falls back to the shared {@code benchmark.runner} property if no
     * per-tenant entry exists.</p>
     */
    private String resolveRunner(String requestedOverride) {
        if (requestedOverride != null && !requestedOverride.isBlank()) {
            return requestedOverride;
        }
        TenantConfig cfg = tenantProperties.getConfigFor(tenantContext.getTenantId());
        if (cfg != null && cfg.getChampionUri() != null && !cfg.getChampionUri().isBlank()) {
            return cfg.getChampionUri();
        }
        return defaultBenchmarkRunner;
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
            client.fetchIdentifiersForLanguage(fetchSet.trim());
            return "Fetched identifiers for set: " + fetchSet.trim()
                    + " -> " + tDataDir + "/guids_" + fetchSet.trim() + ".txt";
        }

        String[] resolvedSets = (sets != null && !sets.isBlank())
                ? sets.split(",")
                : GetOaiPmhIdentifiers.DEFAULT_SETS;

        client.fetchAllLanguageIdentifiers(resolvedSets);
        return "Fetched identifiers for " + resolvedSets.length + " set(s) -> " + tDataDir;
    }

    // ── 2. Run Assessment ────────────────────────────────────────────────────

    public String runAssessment(
            String spreadsheetUri,
            String runnerUri,
            String guidFile,
            java.util.List<String> guidFiles,
            String guid,
            boolean processAll) throws IOException, InterruptedException {

        Path tDataDir    = tenantDataDir();
        Path tResultsDir = tenantResultsDir();
        Files.createDirectories(tDataDir);
        Files.createDirectories(tResultsDir);

        String resolvedAlgorithm = resolveAlgorithm(spreadsheetUri);
        String resolvedRunner    = resolveRunner(runnerUri);

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
                    runner.processSingleFile(resolved.toString());
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
            runner.processSingleFile(resolved.toString());
            return "Processed file: " + resolved + " -> " + tResultsDir;
        }

        if (processAll) {
            runner.processAllSetFiles();
            return "Processed all set files from " + tDataDir
                    + " -> " + tResultsDir;
        }

        Path defaultFile = resolveGuidFile(RunBenchmarkAssessment.DEFAULT_GUIDS_FILE, tDataDir);
        runner.processSingleFile(defaultFile.toString());
        return "Processed default file: " + defaultFile + " -> " + tResultsDir;
    }

    // ── 3. Generate Manifest ─────────────────────────────────────────────────

    public String generateManifest(String overrideResultsDir) throws IOException {
        Path targetDir = (overrideResultsDir != null && !overrideResultsDir.isBlank())
                ? Paths.get(overrideResultsDir).toAbsolutePath().normalize()
                : tenantResultsDir();   // <-- tenant-scoped by default

        if (!Files.isDirectory(targetDir)) {
            throw new IOException("Results directory not found: " + targetDir);
        }

        new GenerateManifest(targetDir).run();
        return "Manifest generated in: " + targetDir;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private Path resolveGuidFile(String filename, Path tDataDir) {
        Path asGiven = Paths.get(filename);
        if (Files.exists(asGiven)) return asGiven;
        return tDataDir.resolve(asGiven.getFileName());
    }

    private static String nvl(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }
}
