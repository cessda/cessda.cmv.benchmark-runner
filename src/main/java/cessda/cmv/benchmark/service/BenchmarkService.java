package cessda.cmv.benchmark.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Service;

import cessda.cmv.benchmark.GenerateManifest;
import cessda.cmv.benchmark.GetOaiPmhIdentifiers;
import cessda.cmv.benchmark.RunBenchmarkAssessment;
import cessda.cmv.benchmark.config.BenchmarkProperties;
import cessda.cmv.benchmark.tenant.TenantContext;
import cessda.cmv.benchmark.tenant.TenantProperties;
import cessda.cmv.benchmark.tenant.TenantProperties.TenantConfig;

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
        return benchmarkProperties.getDataDirPath()
                .resolve(tenantContext.getTenantId())
                .normalize();
    }

    /** /results/{tenantId}/ */
    private Path tenantResultsDir() {
        return benchmarkProperties.getResultsDirPath()
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
    private String resolveAlgorithm(String requestedOverride) {
        if (requestedOverride != null && !requestedOverride.isBlank()) {
            return requestedOverride;
        }
        String tenantValue = currentTenantConfig().effectiveAlgorithm();
        if (tenantValue != null && !tenantValue.isBlank()) {
            return tenantValue;
        }
        String sharedValue = benchmarkProperties.getAlgorithm();
        if (sharedValue != null && !sharedValue.isBlank()) {
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
    private String resolveRunner(String requestedOverride) {
        if (requestedOverride != null && !requestedOverride.isBlank()) {
            return requestedOverride;
        }
        String tenantValue = currentTenantConfig().effectiveRunner();
        if (tenantValue != null && !tenantValue.isBlank()) {
            return tenantValue;
        }
        String sharedValue = benchmarkProperties.getRunner();
        if (sharedValue != null && !sharedValue.isBlank()) {
            return sharedValue;
        }
        throw new IllegalStateException(
                "No runner configured for tenant: " + tenantContext.getTenantId());
    }

    /**
     * Resolves the OAI-PMH base URL for the current tenant.
     *
     * <p>Uses the explicit request override when supplied (an operator
     * changing the URL "for this run only" in the dashboard); otherwise
     * the current tenant's configured
     * {@code tenants.config.<tenantId>.oai-pmh-base-url}; otherwise
     * {@link GetOaiPmhIdentifiers#DEFAULT_OAI_PMH_BASE_URL}. Unlike
     * {@link #resolveAlgorithm} and {@link #resolveRunner}, this never
     * throws for an unconfigured tenant -- there is always a sensible
     * compiled-in default (CESSDA's own catalogue).</p>
     */
    private String resolveOaiPmhBaseUrl(String requestedOverride) {
        if (requestedOverride != null && !requestedOverride.isBlank()) {
            return requestedOverride;
        }
        TenantConfig cfg = currentTenantConfig();
        String tenantValue = cfg.getOaiPmhBaseUrl();
        if (tenantValue != null && !tenantValue.isBlank()) {
            return tenantValue;
        }
        return GetOaiPmhIdentifiers.DEFAULT_OAI_PMH_BASE_URL;
    }

    // ── 1. Fetch OAI-PMH Identifiers ─────────────────────────────────────────

    /**
     * Returns the OAI-PMH base URL that would be used for the current
     * tenant if {@link #fetchIdentifiers} were called with no explicit
     * {@code baseUrl} override.
     *
     * <p>Used to populate the "Fetch identifiers" dashboard page's URL
     * field, so the operator can see and optionally override the
     * default before triggering a fetch or a set listing.</p>
     */
    public String getDefaultOaiPmhBaseUrl() {
        return resolveOaiPmhBaseUrl(null);
    }

    /**
     * Calls {@code verb=ListSets} against the given (or, if blank, the
     * current tenant's default) OAI-PMH endpoint and returns every set
     * it reports.
     *
     * <p>Used to populate the "Fetch identifiers" dashboard page's
     * checkable set list. There is deliberately no fallback to a
     * static or compiled-in list: the live endpoint is the only source
     * that can't drift out of sync with itself, and an operator who
     * has overridden the URL for this run needs to see that specific
     * endpoint's actual sets, not CESSDA's.</p>
     *
     * @param baseUrl OAI-PMH base URL override, or {@code null}/blank
     *                to use the current tenant's configured default
     * @param verb    OAI-PMH verb, or {@code null}/blank for
     *                {@link GetOaiPmhIdentifiers#DEFAULT_VERB}
     *                (ListSets does not depend on this, but the same
     *                client is reused for consistency)
     * @throws IOException          if the request fails or the
     *                               response cannot be parsed
     * @throws InterruptedException if interrupted while waiting for
     *                               the HTTP response
     */
    public java.util.List<GetOaiPmhIdentifiers.SetInfo> listAvailableSets(
            String baseUrl, String verb) throws IOException, InterruptedException {
        String resolvedBase = resolveOaiPmhBaseUrl(baseUrl);
        String resolvedVerb = nvl(verb, GetOaiPmhIdentifiers.DEFAULT_VERB);
        GetOaiPmhIdentifiers client = new GetOaiPmhIdentifiers(
                resolvedBase, resolvedVerb, GetOaiPmhIdentifiers.DEFAULT_METADATA_PREFIX, null);
        return client.listSets();
    }

    public String fetchIdentifiers(
            String baseUrl,
            String verb,
            String metadataPrefix,
            String sets,
            String fetchSet) throws IOException, InterruptedException {

        Path tDataDir = tenantDataDir();
        Files.createDirectories(tDataDir);

        String resolvedBase   = resolveOaiPmhBaseUrl(baseUrl);
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

        // Isolate per-set failures rather than aborting the whole batch
        // at the first one -- an operator selecting several sets from
        // the dashboard expects one bad or momentarily-unreachable set
        // not to cost them every other set they also selected, the
        // same way a single failing GUID doesn't abort a whole
        // RunBenchmarkAssessment batch.
        java.util.List<String> succeeded = new java.util.ArrayList<>();
        java.util.Map<String, String> failed = new java.util.LinkedHashMap<>();
        for (String rawSet : resolvedSets) {
            String set = rawSet == null ? "" : rawSet.trim();
            if (set.isEmpty()) continue;
            try {
                client.fetchIdentifiersForSet(set);
                succeeded.add(set);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                failed.put(set, ie.getMessage());
            } catch (IOException ioe) {
                failed.put(set, ioe.getMessage());
            }
        }

        if (succeeded.isEmpty() && !failed.isEmpty()) {
            throw new IOException("Failed to fetch identifiers for all " + failed.size()
                    + " selected set(s): " + describeFailures(failed));
        }

        String message = "Fetched identifiers for " + succeeded.size() + " of "
                + resolvedSets.length + " set(s) -> " + tDataDir;
        if (!failed.isEmpty()) {
            message += " (failed: " + describeFailures(failed) + ")";
        }
        return message;
    }

    private static String describeFailures(java.util.Map<String, String> failed) {
        StringBuilder sb = new StringBuilder();
        for (var entry : failed.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(entry.getKey()).append(": ").append(entry.getValue());
        }
        return sb.toString();
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
        if (benchmarkProperties.getBackoffBetweenProcessGuidMs() != null) {
            runner.setBackoffBetweenProcessGuid(
                    benchmarkProperties.getBackoffBetweenProcessGuidMs().toMillis());
        }

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