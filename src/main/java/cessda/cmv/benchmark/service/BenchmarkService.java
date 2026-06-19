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

@Service
public class BenchmarkService {

    @Value("${benchmark.data-dir:/data}")
    private String dataDir;

    @Value("${benchmark.results-dir:/results}")
    private String resultsDir;

    @Value("${benchmark.runner}")
    private String benchmarkRunner;

    @Value("${benchmark.algorithm}")
    private String benchmarkAlgorithm;

    private final TenantContext tenantContext;

    public BenchmarkService(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
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

    // ── 1. Fetch OAI-PMH Identifiers ─────────────────────────────────────────

    public String fetchIdentifiers(
            String baseUrl,
            String verb,
            String metadataPrefix,
            String sets,
            String fetchSet) throws IOException, InterruptedException {

        Path tDataDir = tenantDataDir();
        Files.createDirectories(tDataDir);

        // Pass the tenant data directory to the CLI class via its constructor
        // (add a Path-accepting constructor to GetOaiPmhIdentifiers — see note below)
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
            String guidFile,
            String guid,
            boolean processAll) throws IOException, InterruptedException {

        Path tDataDir    = tenantDataDir();
        Path tResultsDir = tenantResultsDir();
        Files.createDirectories(tDataDir);
        Files.createDirectories(tResultsDir);

        String resolvedUri = nvl(spreadsheetUri, benchmarkAlgorithm);

        // Pass tenant-scoped dirs explicitly — no system property side-effects
        RunBenchmarkAssessment runner =
                new RunBenchmarkAssessment(resolvedUri, benchmarkRunner,
                                           tDataDir, tResultsDir);

        if (guid != null && !guid.isBlank()) {
            runner.processSingleGuid(guid.trim());
            return "Processed single GUID: " + guid.trim()
                    + " -> " + tResultsDir;
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