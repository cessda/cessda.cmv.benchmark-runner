/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package cessda.cmv.benchmark.service;

import cessda.cmv.benchmark.GenerateManifest;
import cessda.cmv.benchmark.GetOaiPmhIdentifiers;
import cessda.cmv.benchmark.RunBenchmarkAssessment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Spring service that delegates to the three existing CLI classes.
 *
 * File I/O is rooted at two externally-mounted Docker named volumes whose
 * paths are injected via application.properties:
 *   benchmark.data-dir    - Docker volume benchmark-data mounted at /data;
 *                           holds guids_*.txt files.
 *   benchmark.results-dir - Docker volume benchmark-results mounted at /results;
 *                           holds JSON result files and summary.json.
 *
 * Both volume paths are also published as JVM system properties so that the
 * existing CLI classes (GetOaiPmhIdentifiers and RunBenchmarkAssessment) can
 * resolve them at runtime without requiring further structural changes.
 */
@Service
public class BenchmarkService {

    /** Root directory for guids_*.txt files (Docker volume: benchmark-data). */
    @Value("${benchmark.data-dir:/data}")
    private String dataDir;

    /** Root directory for result JSON files (Docker volume: benchmark-results). */
    @Value("${benchmark.results-dir:/results}")
    private String resultsDir;

    // -------------------------------------------------------------------------
    // 1. Fetch OAI-PMH Identifiers
    // -------------------------------------------------------------------------

    /**
     * Fetches identifiers from an OAI-PMH endpoint and writes guids_*.txt
     * files to the data volume (/data).
     */
    public String fetchIdentifiers(
            String baseUrl,
            String verb,
            String metadataPrefix,
            String sets,
            String fetchSet) throws IOException, InterruptedException {

        publishSystemProperties();
        Files.createDirectories(Paths.get(dataDir));

        String resolvedBase   = nvl(baseUrl,        GetOaiPmhIdentifiers.DEFAULT_OAI_PMH_BASE_URL);
        String resolvedVerb   = nvl(verb,            GetOaiPmhIdentifiers.DEFAULT_VERB);
        String resolvedPrefix = nvl(metadataPrefix,  GetOaiPmhIdentifiers.DEFAULT_METADATA_PREFIX);

        GetOaiPmhIdentifiers client =
                new GetOaiPmhIdentifiers(resolvedBase, resolvedVerb, resolvedPrefix);

        if (fetchSet != null && !fetchSet.isBlank()) {
            client.fetchIdentifiersForLanguage(fetchSet.trim());
            return "Fetched identifiers for set: " + fetchSet.trim()
                    + " -> " + dataDir + "/guids_" + fetchSet.trim() + ".txt";
        }

        String[] resolvedSets = (sets != null && !sets.isBlank())
                ? sets.split(",")
                : GetOaiPmhIdentifiers.DEFAULT_SETS;

        client.fetchAllLanguageIdentifiers(resolvedSets);
        return "Fetched identifiers for " + resolvedSets.length + " set(s) -> " + dataDir;
    }

    // -------------------------------------------------------------------------
    // 2. Run Benchmark Assessment
    // -------------------------------------------------------------------------

    /**
     * Reads guids_*.txt files from the data volume, posts each GUID URL to the
     * Champion API, and writes JSON result files to the results volume.
     */
    public String runAssessment(
            String spreadsheetUri,
            String guidFile,
            String guid,
            boolean processAll) throws IOException, InterruptedException {

        publishSystemProperties();
        Files.createDirectories(Paths.get(dataDir));
        Files.createDirectories(Paths.get(resultsDir));

        String resolvedUri = nvl(spreadsheetUri, RunBenchmarkAssessment.BENCHMARK_ALGORITHM_URI);
        RunBenchmarkAssessment runner = new RunBenchmarkAssessment(resolvedUri);

        if (guid != null && !guid.isBlank()) {
            runner.processSingleGuid(guid.trim());
            return "Processed single GUID: " + guid.trim()
                    + " -> results written to " + resultsDir;
        }

        if (guidFile != null && !guidFile.isBlank()) {
            String resolvedFile = resolveGuidFile(guidFile.trim());
            runner.processSingleFile(resolvedFile);
            return "Processed file: " + resolvedFile
                    + " -> results written to " + resultsDir;
        }

        if (processAll) {
            runner.processAllSetFiles();
            return "Processed all default set files from " + dataDir
                    + " -> results written to " + resultsDir;
        }

        String defaultFile = resolveGuidFile(RunBenchmarkAssessment.DEFAULT_GUIDS_FILE);
        runner.processSingleFile(defaultFile);
        return "Processed default file: " + defaultFile
                + " -> results written to " + resultsDir;
    }

    // -------------------------------------------------------------------------
    // 3. Generate Manifest
    // -------------------------------------------------------------------------

    /**
     * Reads JSON result files from the results volume and produces
     * results/summary.json and paginated page files consumed by the dashboard.
     *
     * This is the API equivalent of the first command in start-dashboard.sh.
     */
    public String generateManifest(String overrideResultsDir) throws IOException {
        String targetDir = (overrideResultsDir != null && !overrideResultsDir.isBlank())
                ? overrideResultsDir.trim()
                : resultsDir;

        Path path = Paths.get(targetDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IOException("Results directory not found: " + path);
        }

        new GenerateManifest(path).run();
        return "Manifest generated in: " + path;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Publishes the configured volume paths as JVM system properties so the
     * patched CLI classes can read them without Spring dependency injection.
     */
    private void publishSystemProperties() {
        System.setProperty("benchmark.data-dir",    dataDir);
        System.setProperty("benchmark.results-dir", resultsDir);
    }

    /**
     * Resolves a guids filename to an absolute path under the data volume if
     * the file does not already exist at the supplied path.
     */
    private String resolveGuidFile(String filename) {
        Path asGiven = Paths.get(filename);
        if (Files.exists(asGiven)) {
            return filename;
        }
        Path inDataDir = Paths.get(dataDir, asGiven.getFileName().toString());
        // Return the data-volume path regardless; RunBenchmarkAssessment will
        // throw FileNotFoundException with a clear message if the file is absent.
        return inDataDir.toAbsolutePath().toString();
    }

    private static String nvl(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }
}
