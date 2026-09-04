/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package cessda.cmv.benchmark;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLHandshakeException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cessda.cmv.benchmark.config.BenchmarkProperties;
import cessda.cmv.benchmark.tenant.TenantProperties;

/**
 * Reads GUID files produced by {@link GetOaiPmhIdentifiers} (each line
 * is a full OAI-PMH GetRecord URL) and submits every URL to the
 * Champion benchmark assessment API. One JSON result file is written
 * per GUID into a subdirectory of {@link #resultsDir} named after the
 * input file (minus its extension).
 *
 * <p>
 * This class is a plain Java object, not a Spring-managed bean. It is
 * constructed directly, either by {@code BenchmarkService} with
 * tenant-scoped spreadsheetUri, championUri, data, and results paths resolved
 * per request, or by {@link #main(String[])} for standalone
 * command-line use. A single shared Spring singleton would be
 * incompatible with multi-tenancy, since the spreadsheetUri and championUri
 * URIs — and the data and results directories — differ per tenant and
 * must be resolved fresh for each invocation.
 * </p>
 *
 * <h2>Command-line options</h2>
 *
 * <pre>
 *   -s, --spreadsheetUri &lt;uri&gt;   SpreadsheetUri URI (overrides benchmark.algorithm)
 *   -r, --championUri &lt;uri&gt;        championUri URI (overrides benchmark.runner)
 *   -t, --tenant &lt;tenant-id&gt;  Resolve algorithm/runner and data/results
 *                              directories from tenants.config.&lt;tenant-id&gt;
 *                              (an explicit -s/-r still overrides the
 *                              resolved algorithm/runner). Omit for the
 *                              shared top-level benchmark.* defaults.
 *   -p, --process-file &lt;file&gt; Process a single named GUID file
 *   -P, --process-all         Process all guids_XX.txt files for the
 *                              default set list
 *   -g, --guid &lt;url&gt;          Process a single GetRecord URL supplied
 *                              on the command line
 *   -f, --filename &lt;file&gt;     GUIDs filename (legacy single-file mode)
 *   -h, --help                Show this help message
 * </pre>
 *
 * <p>
 * If none of the mode flags are given, the file specified by
 * {@code -f} / {@code --filename} (default: {@value #DEFAULT_GUIDS_FILE})
 * is processed (legacy single-file mode).
 * </p>
 *
 * <p>
 * {@code -t} / {@code --tenant} makes this CLI tenant-aware, resolving
 * the same {@code tenants.config.<tenant-id>} entry the REST API uses
 * ({@code algorithm}, {@code runner}, and per-tenant
 * {@code {data,results}-dir/<tenant-id>/} subdirectories) instead of
 * the shared top-level {@code benchmark.*} defaults. This is intended
 * for driving a per-tenant run from an external pipeline (e.g. CI/QA
 * automation) without going through the REST API. It is not accepted
 * by {@link GetOaiPmhIdentifiers}'s own {@code -t} / {@code --tenant}
 * flag, which only selects an output subdirectory and does not affect
 * which OAI-PMH endpoint is queried.
 * </p>
 */
public class RunBenchmarkAssessment {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /** Default GUID file processed in legacy single-file mode. */
    public static final String DEFAULT_GUIDS_FILE = "guids_hr.txt";

    private static final String DEFAULT_OAI_PMH_BASE_URL = "https://datacatalogue.cessda.eu/oai-pmh/v0/oai?verb=GetRecord&metadataPrefix=oai_ddi25&identifier=";

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Set codes whose {@code guids_XX.txt} files are processed
     * by the {@code -P} / {@code --process-all} flag.
     */
    public static final String[] DEFAULT_SETS = {
            "de", "el", "en", "fi", "fr", "hr", "nl", "sl", "sl-SI", "sv"
    };

    private static final String OUTPUT_DIR = "results";
    private static final String HEADER_VALUE = "application/json";
    private static final String ACCEPT = "Accept";
    private static final String CONTENT_TYPE = "Content-Type";

    // Log / status message templates
    private static final String NOGUIDS = "No GUIDs found to process. Exiting.";
    private static final String PROCCOMP = "Processing completed!";
    private static final String FOUNDGUIDS = "Found %d GUID(s) to process";
    private static final String PROCERROR = "Error processing GUID %s: %s";
    private static final String TASKWAIT = "Waiting for all tasks to complete...";
    private static final String TASKTOOLONG = "Some tasks did not complete in time!";
    private static final String TASKSUCCESS = "All tasks completed successfully.";
    private static final String REQSEND = "Sending request to ";
    private static final String FILESAVEERR = "Could not save error file: ";
    private static final String PROCFAIL = "\u2717 Failed to process GUID ";
    private static final String RESPSAVED = "\u2713 Saved response for GUID ";

    // CLI option names
    private static final String SPREADSHEET_ARG = "spreadsheetUri";
    private static final String PROCESS_ALL_ARG = "process-all";
    private static final String PROCESS_FILE_ARG = "process-file";
    private static final String GUID_ARG = "guid";
    private static final String FILENAME_ARG = "filename";
    private static final String TENANT_ARG = "tenant";

    private final Duration requestTimeout;

    private static final Logger logger = Logger.getLogger(RunBenchmarkAssessment.class.getName());

    private static final Semaphore REQUEST_SEMAPHORE = new Semaphore(2);

    // -----------------------------------------------------------------------
    // Instance state
    // -----------------------------------------------------------------------

    /**
     * URI of the benchmark assessment spreadsheetUri, supplied by the
     * caller (e.g. resolved per-tenant by {@code BenchmarkService}, or
     * read from {@code application.yml} / CLI flags in standalone
     * mode via {@link #main(String[])}).
     */
    private String spreadsheetUri;

    /**
     * URI of the FAIR Champion championUri instance, supplied by the
     * caller in the same way as {@link #spreadsheetUri}.
     */
    private String championUri;

    /**
     * Name of the GUID file currently being processed; may be
     * overridden temporarily by {@link #processSingleFile(String)}.
     */
    private String guidsFilename;

    private final HttpClient httpClient;

    private Path dataDir = Paths.get("data");

    private Path resultsDir = Paths.get(OUTPUT_DIR);

    /**
     * Pause, in milliseconds, observed before submitting each GUID
     * after the first within a batch (see {@link #processGuids}).
     * Defaults to
     * {@link BenchmarkProperties#DEFAULT_BACKOFF_BETWEEN_PROCESS_GUID_MS}
     * and is overwritten with the configured
     * {@code benchmark.backoff-between-process-guid-ms} value, when
     * present, by both {@link #main(String[])} (via {@link CliConfig})
     * and {@code BenchmarkService}.
     */
    private long backoffBetweenProcessGuid =
            BenchmarkProperties.DEFAULT_BACKOFF_BETWEEN_PROCESS_GUID_MS.toMillis();


    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Creates an instance configured with explicit spreadsheetUri, championUri,
     * and timeout values.
     *
     * <p>
     * This constructor is used by {@link #main(String[])} for
     * standalone command-line use, where the spreadsheetUri and championUri
     * URIs are read from {@code application.yml} (via the nested
     * {@link CliConfig} Spring configuration) and may be overridden
     * by CLI flags before processing begins.
     * </p>
     *
     * @param spreadsheetUri URI of the benchmark assessment
     *                           spreadsheetUri
     * @param championUri    URI of the FAIR Champion championUri
     * @param connectTimeout     HTTP connect timeout, in seconds
     * @param requestTimeout     HTTP request timeout, in seconds
     */
    public RunBenchmarkAssessment(
            String spreadsheetUri,
            String championUri,
            int connectTimeout,
            int requestTimeout) {

        this.requestTimeout = Duration.ofSeconds(requestTimeout);
        this.spreadsheetUri = spreadsheetUri;
        this.championUri = championUri;
        this.guidsFilename = DEFAULT_GUIDS_FILE;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeout))
                .build();
    }

    /**
     * Creates an instance scoped to a specific tenant's data and
     * results directories, using default timeout values of 30s
     * (connect) and 120s (request).
     *
     * <p>
     * This is the constructor used by {@code BenchmarkService}, which
     * resolves {@code spreadsheetUri} and {@code championUri}
     * per tenant (from {@code tenants.config.<tenantId>} in
     * {@code application.yml}, with an explicit per-request override
     * taking priority) before calling this constructor. Each call
     * creates a fresh instance — there is no shared singleton — so
     * concurrent requests for different tenants never interfere with
     * one another.
     * </p>
     *
     * @param spreadsheetUri URI of the benchmark assessment
     *                           spreadsheetUri for this tenant
     * @param championUri    URI of the FAIR Champion championUri for
     *                           this tenant
     * @param dataDir            tenant-scoped data directory
     * @param resultsDir         tenant-scoped results directory
     */
    public RunBenchmarkAssessment(String spreadsheetUri, String championUri,
                                   Path dataDir, Path resultsDir) {
        this.spreadsheetUri = spreadsheetUri;
        this.championUri     = championUri;
        this.dataDir    = dataDir;
        this.resultsDir = resultsDir;
        this.requestTimeout = Duration.ofSeconds(120);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Convenience constructor for direct instantiation outside of a
     * Spring context. Uses default timeout values of 30s (connect)
     * and 120s (request), and default paths of {@code data/} and
     * {@code results/} for the data and results directories.
     *
     * <p>Use {@link #RunBenchmarkAssessment(String, String, Path, Path)}
     * when tenant-scoped directory isolation is required.</p>
     *
     * @param spreadsheetUri URI of the benchmark assessment spreadsheetUri
     * @param championUri    URI of the FAIR Champion championUri
     */
    public RunBenchmarkAssessment(
            String spreadsheetUri,
            String championUri) {
        this(spreadsheetUri, championUri, 30, 120);
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    /**
     * Returns the URI of the benchmark assessment spreadsheetUri.
     * This value is supplied by the caller — either resolved per
     * tenant by {@code BenchmarkService}, or read from
     * {@code application.yml} / overridden via the {@code -s} CLI
     * flag in standalone mode.
     *
     * @return the spreadsheetUri URI string
     */
    public String getSpreadsheetUri() {
        return spreadsheetUri;
    }

    /**
     * Returns the URI of the FAIR Champion championUri instance.
     * This value is supplied by the caller in the same way as
     * {@link #getSpreadsheetUri()}.
     *
     * @return the championUri URI string
     */
    public String getChampionUri() {
        return championUri;
    }

    /**
     * Returns the pause, in milliseconds, observed before submitting
     * each GUID after the first within a batch.
     *
     * @return the configured backoff, in milliseconds
     */
    public long getBackoffBetweenProcessGuid() {
        return backoffBetweenProcessGuid;
    }

    /**
     * Overrides the default pause between successive GUID submissions.
     * Called with the configured
     * {@code benchmark.backoff-between-process-guid-ms} value, when
     * present, by both {@link #main(String[])} and
     * {@code BenchmarkService} — the compiled-in
     * {@link BenchmarkProperties#DEFAULT_BACKOFF_BETWEEN_PROCESS_GUID_MS}
     * applies otherwise.
     *
     * @param backoffBetweenProcessGuidMs the backoff to use, in
     *                                    milliseconds
     */
    public void setBackoffBetweenProcessGuid(long backoffBetweenProcessGuidMs) {
        this.backoffBetweenProcessGuid = backoffBetweenProcessGuidMs;
    }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    /**
     * Spring configuration used only by {@link #main(String[])} to
     * load {@code application.yml} and bind the shared
     * {@code benchmark.algorithm} / {@code benchmark.runner}
     * properties, plus {@code tenants.config}, for standalone
     * command-line use.
     *
     * <p>
     * This is deliberately separate from {@link RunBenchmarkAssessment}
     * itself, which is never a Spring bean — see the class-level
     * Javadoc for why a shared singleton is incompatible with
     * multi-tenancy. Standalone CLI use has no concept of "the current
     * tenant" unless {@code -t} / {@code --tenant} is given, in which
     * case {@link #main(String[])} resolves that tenant's algorithm,
     * runner, and data/results directories from {@code tenants.config}
     * itself, the same way {@code BenchmarkService} does for the REST
     * API. Without {@code -t}, it falls back to the shared top-level
     * {@code benchmark.algorithm} / {@code benchmark.runner}
     * properties. Both of those top-level properties default to an
     * empty string if absent, rather than failing context startup,
     * since a CLI user may always supply {@code -s} / {@code -r}
     * instead.
     * </p>
     *
     * <p>
     * Registering {@link TenantProperties} here means the CLI now also
     * performs the same bean validation the REST API's context does:
     * if {@code application.yml} configures any {@code tenants.config}
     * entry missing a {@code title} or {@code footer}, context startup
     * fails — even for a run that never passes {@code -t}. Every
     * tenant configured for the REST API should already satisfy this,
     * since it is validated there too.
     * </p>
     *
     * <p>
     * Deliberately annotated {@code @Configuration} +
     * {@code @EnableAutoConfiguration} rather than
     * {@code @SpringBootApplication}. The latter also implies
     * {@code @SpringBootConfiguration}, which Spring Boot's test
     * infrastructure auto-discovers when a test does not specify
     * {@code classes = ...} explicitly — having two such classes on
     * the classpath (this one and {@code BenchmarkApplication}) makes
     * that auto-discovery ambiguous and fails every slice test in the
     * application. No {@code @ComponentScan} is needed here either,
     * since {@link TenantProperties} is registered explicitly via
     * {@code @EnableConfigurationProperties} rather than discovered as
     * a {@code @Component}, and this configuration declares its one
     * {@code @Bean} method directly.
     * </p>
     */
    @Configuration
    @EnableConfigurationProperties({BenchmarkProperties.class, TenantProperties.class})
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    static class CliConfig {

        @Bean
        RunBenchmarkAssessment runBenchmarkAssessment(
                BenchmarkProperties benchmarkProperties) {
            RunBenchmarkAssessment runner = new RunBenchmarkAssessment(
                    benchmarkProperties.getAlgorithm(),
                    benchmarkProperties.getRunner());
            if (benchmarkProperties.getBackoffBetweenProcessGuidMs() != null) {
                runner.setBackoffBetweenProcessGuid(
                        benchmarkProperties.getBackoffBetweenProcessGuidMs().toMillis());
            }
            return runner;
        }
    }

    /**
     * Bootstraps a headless Spring context so that
     * {@code application.yml} is loaded and the shared
     * {@code benchmark.algorithm} / {@code benchmark.runner}
     * properties are bound before any processing begins. A CLI
     * override for either URI (via {@code -s} / {@code --spreadsheet}
     * or {@code -r} / {@code --championUri}) is applied after the context
     * is ready.
     *
     * <p>
     * By default this standalone entry point uses the shared top-level
     * {@code benchmark.algorithm} / {@code benchmark.runner} properties
     * and the shared top-level {@code benchmark.data-dir} /
     * {@code benchmark.results-dir} directories, not any
     * tenant-specific {@code tenants.config} entry. Passing {@code -t}
     * / {@code --tenant <tenant-id>} switches to that tenant's own
     * algorithm, runner, and {@code {data,results}-dir/<tenant-id>/}
     * directories, resolved from {@code tenants.config.<tenant-id>} —
     * mirroring exactly how the REST API
     * ({@code POST /api/run-assessment} with a tenant's
     * {@code X-API-Key}) resolves them for that same tenant. An
     * explicit {@code -s} / {@code -r} always overrides the resolved
     * algorithm/runner, whether or not {@code -t} is also given, so a
     * one-off algorithm/runner substitution for a tenant run is still
     * possible. If {@code -t} names a tenant with no
     * {@code tenants.config} entry, an error is logged and processing
     * stops before anything is submitted.
     * </p>
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        logger.setLevel(Level.INFO);

        // Boot Spring without a web server so application.yml is
        // loaded and the shared benchmark.* properties are bound.
        ApplicationContext ctx = new SpringApplicationBuilder(
                CliConfig.class)
                .web(WebApplicationType.NONE)
                .run(args);

        RunBenchmarkAssessment client = ctx.getBean(RunBenchmarkAssessment.class);

        CommandLine cmd;
        try {
            cmd = parseArgs(args);
        } catch (IOException e) {
            logSevere("Failed to parse arguments: %s", e.getMessage());
            return;
        }

        // Resolve tenant-scoped algorithm/runner/data-dir/results-dir
        // first, if -t/--tenant was given. This runs before the -s/-r
        // override blocks below, so an explicit -s/-r flag still always
        // wins even for a tenant-scoped run — the same precedence
        // BenchmarkService applies for the REST API (explicit override
        // > tenant config > shared top-level default).
        if (cmd.hasOption(TENANT_ARG)) {
            String tenantId = cmd.getOptionValue(TENANT_ARG);
            TenantResolution resolution = resolveTenant(
                    ctx.getBean(TenantProperties.class),
                    ctx.getBean(BenchmarkProperties.class),
                    tenantId);
            if (resolution == null) {
                logSevere("Unknown tenant '%s' — no tenants.config.%s entry in application.yml.",
                        tenantId, tenantId);
                return;
            }

            client.spreadsheetUri = resolution.algorithm();
            client.championUri = resolution.runner();
            client.dataDir = resolution.dataDir();
            client.resultsDir = resolution.resultsDir();
            logInfo("Using tenant '%s' (data-dir: %s, results-dir: %s)",
                    tenantId, client.dataDir, client.resultsDir);
        }

        // Allow the CLI to override the injected championUri URI.
        if (cmd.hasOption("championUri")) {
            client.championUri = cmd.getOptionValue("championUri");
}

        // Allow the CLI to override the injected spreadsheetUri URI.
        if (cmd.hasOption(SPREADSHEET_ARG)) {
            client.spreadsheetUri = cmd.getOptionValue(SPREADSHEET_ARG);
        }
        if (client.spreadsheetUri == null || client.spreadsheetUri.isBlank()) {
            logSevere("No benchmark.algorithm configured and no --%s override supplied.",
                    SPREADSHEET_ARG);
            return;
        }
        if (client.championUri == null || client.championUri.isBlank()) {
            logSevere("No benchmark.runner configured and no --championUri override supplied.");
            return;
        }
        logInfo("Using spreadsheetUri URI:  %s", client.spreadsheetUri);
        logInfo("Using championUri URI:     %s", client.championUri);

        try {
            Files.createDirectories(client.resultsDir);

            boolean processAll = cmd.hasOption(PROCESS_ALL_ARG);
            boolean processFile = cmd.hasOption(PROCESS_FILE_ARG);
            boolean singleGuid = cmd.hasOption(GUID_ARG);

            if (processAll) {
                client.processAllSetFiles();
            } else if (processFile) {
                client.processSingleFile(
                        cmd.getOptionValue(PROCESS_FILE_ARG));
            } else if (singleGuid) {
                client.processSingleGuid(cmd.getOptionValue(GUID_ARG));
            } else {
                // Legacy mode: process the file given by -f/--filename.
                if (cmd.hasOption(FILENAME_ARG)) {
                    client.guidsFilename = cmd.getOptionValue(FILENAME_ARG);
                }
                List<String> guids = client.readGuidsFromResource(client.guidsFilename);
                if (guids.isEmpty()) {
                    logInfo(NOGUIDS);
                } else {
                    logInfo(FOUNDGUIDS, guids.size());
                    client.processGuids(guids, null, null);
                    logInfo(PROCCOMP);
                }
            }

        } catch (IOException ioe) {
            logSevere("Error: %s", ioe.getMessage());
            ioe.printStackTrace();
        } catch (InterruptedException ie) {
            logSevere("Processing was interrupted: %s",
                    ie.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The algorithm, runner, and data/results directories resolved for
     * one tenant by {@link #resolveTenant}.
     *
     * @param algorithm  the tenant's effective algorithm URI
     * @param runner     the tenant's effective runner URI
     * @param dataDir    {@code {data-dir}/{tenantId}/}, absolute and
     *                   normalized
     * @param resultsDir {@code {results-dir}/{tenantId}/}, absolute and
     *                   normalized
     */
    record TenantResolution(String algorithm, String runner, Path dataDir, Path resultsDir) {
    }

    /**
     * Resolves one tenant's algorithm, runner, and data/results
     * directories from {@code tenants.config.<tenantId>}, mirroring
     * {@code BenchmarkService}'s own {@code resolveAlgorithm} /
     * {@code resolveRunner} / {@code tenantDataDir} /
     * {@code tenantResultsDir} logic for the REST API.
     *
     * @param tenantProperties    the bound {@code tenants.*} properties
     * @param benchmarkProperties the bound shared {@code benchmark.*}
     *                            properties, used for their
     *                            {@code data-dir} / {@code results-dir}
     *                            roots only
     * @param tenantId            the tenant ID to resolve (not an API
     *                            key)
     * @return the resolved algorithm/runner/directories, or
     *         {@code null} if no {@code tenants.config.<tenantId>}
     *         entry exists
     */
    static TenantResolution resolveTenant(
            TenantProperties tenantProperties,
            BenchmarkProperties benchmarkProperties,
            String tenantId) {

        TenantProperties.TenantConfig tenantConfig = tenantProperties.getConfigFor(tenantId);
        if (tenantConfig == null) {
            return null;
        }
        return new TenantResolution(
                tenantConfig.effectiveAlgorithm(),
                tenantConfig.effectiveRunner(),
                benchmarkProperties.getDataDirPath().resolve(tenantId).normalize(),
                benchmarkProperties.getResultsDirPath().resolve(tenantId).normalize());
    }

    // -----------------------------------------------------------------------
    // Multi-file processing
    // -----------------------------------------------------------------------

    /**
     * Iterates over every set in {@link #DEFAULT_SETS}, resolves the
     * corresponding {@code guids_XX.txt} file, and processes it if
     * found. Missing files are logged and skipped rather than causing
     * a hard failure.
     *
     * @throws IOException          if a file operation fails
     * @throws InterruptedException if processing is interrupted
     */
    public void processAllSetFiles()
            throws IOException, InterruptedException {

        logInfo("Processing GUID files for all sets...");
        for (String set : DEFAULT_SETS) {
            String filename = "guids_" + set + ".txt";
            logInfo("--- Processing file: %s ---", filename);
            try {
                processSingleFile(filename);
            } catch (FileNotFoundException fnfe) {
                logSevere("Skipping %s — file not found: %s",
                        filename, fnfe.getMessage());
            }
        }
        logInfo("Finished processing all set files.");
    }

    /**
     * Reads GUIDs from the named file and processes them.
     *
     * <p>
     * Each non-blank, non-comment line is treated as a full
     * GetRecord URL as produced by {@link GetOaiPmhIdentifiers}.
     * </p>
     *
     * @param filename name of the file to read (classpath resources
     *                 are checked first, then the current directory)
     * @throws IOException          if a file operation fails
     * @throws InterruptedException if processing is interrupted
     */
    public void processSingleFile(String filename)
            throws IOException, InterruptedException {

        String previousFilename = guidsFilename;
        guidsFilename = filename;
        try {
            List<String> guids = readGuidsFromResource(filename);
            if (guids.isEmpty()) {
                logInfo("No GUIDs found in %s. Skipping.", filename);
                return;
            }
            logInfo(FOUNDGUIDS + " in " + filename, guids.size());
            String set = extractSetFromFilename(filename);
            String subDir = deriveSubdirectory(filename);
            processGuids(guids, set, subDir);
            logInfo(PROCCOMP + " (" + filename + ")");
        } finally {
            guidsFilename = previousFilename;
        }
    }

    /**
     * Processes a single GetRecord URL supplied directly on the
     * command line.
     *
     * @param guid the full GetRecord URL to submit
     * @throws IOException          if a file operation fails
     * @throws InterruptedException if processing is interrupted
     */
    public void processSingleGuid(String guid)
            throws IOException, InterruptedException {

        logInfo("Processing single GUID: %s", guid);
        processOneGuid(guid, 0, null, null, championUri);
        logInfo(PROCCOMP);
    }

    // -----------------------------------------------------------------------
    // GUID file reading
    // -----------------------------------------------------------------------

    /**
     * Reads GUIDs from the file identified by {@link #guidsFilename}.
     * The classpath (resources) is checked first, then the path as
     * given, then the configured {@link #dataDir}.
     *
     * @param filename the name of the file to read (ignored if using the
     *                 default {@code guidsFilename} which is already set to the
     *                 default)
     * @return an immutable list of GUID / GetRecord URL strings
     * @throws IOException if the file cannot be found or read
     */
    private List<String> readGuidsFromResource(String filename) throws IOException {

        try (InputStream is = getClass()
                .getClassLoader()
                .getResourceAsStream(guidsFilename)) {

            if (is != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                is, StandardCharsets.UTF_8))) {
                    return reader.lines()
                            .map(String::trim)
                            .filter(l -> !l.isBlank()
                                    && !l.startsWith("#"))
                            .toList();
                }
            }
        }

        // Try the path as given (covers absolute paths and paths relative to cwd).
        Path guidsPath = Paths.get(guidsFilename);
        if (Files.exists(guidsPath)) {
            return Files.readAllLines(guidsPath, StandardCharsets.UTF_8)
                    .stream()
                    .map(String::trim)
                    .filter(l -> !l.isBlank() && !l.startsWith("#"))
                    .toList();
        }

        // Fall back to resolving the bare filename under the tenant data directory.
        Path inDataDir = dataDir.resolve(Paths.get(guidsFilename).getFileName());
        if (Files.exists(inDataDir)) {
            return Files.readAllLines(inDataDir, StandardCharsets.UTF_8)
                    .stream()
                    .map(String::trim)
                    .filter(l -> !l.isBlank() && !l.startsWith("#"))
                    .toList();
        }

        throw new FileNotFoundException(
                "Could not find " + guidsFilename
                        + " in resources, current directory, or data directory ("
                        + dataDir + ")");
    }

    // -----------------------------------------------------------------------
    // GUID processing
    // -----------------------------------------------------------------------

    /**
     * Submits all GUIDs to the Champion API using a fixed thread pool
     * of five workers and awaits completion for up to ten minutes.
     * Every submission after the first ({@code index > 0}) is preceded
     * by a {@link #backoffBetweenProcessGuid}-millisecond pause, to
     * ease the burst of concurrent requests Champion otherwise sees.
     *
     * @param guids  list of GetRecord URLs to submit
     * @param set    set name used for error-file naming
     *               (may be {@code null})
     * @param subDir subdirectory under {@code resultsDir} for
     *               results (may be {@code null})
     * @throws InterruptedException if the executor is interrupted
     *                              while waiting
     */
    private void processGuids(
            List<String> guids,
            String set,
            String subDir) throws InterruptedException {

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            for (int i = 0; i < guids.size(); i++) {
                final String rawGuid = guids.get(i);
                final int index = i;

                if (rawGuid == null || rawGuid.isBlank()) {
                    logInfo("Skipping blank GUID at index %d", index);
                    continue;
                }

                final String guid = normaliseGuid(rawGuid);

                CompletableFuture.runAsync(() -> {
                    try {
                        REQUEST_SEMAPHORE.acquire();
                        try {
                            if (index > 0) {
                                Thread.sleep(backoffBetweenProcessGuid);
                            }
                            processOneGuid(guid, index, set, subDir, championUri);
                        } finally {
                            REQUEST_SEMAPHORE.release();
                        }
                    } catch (IOException ioe) {
                        // processOneGuid already calls saveErrorFile itself
                        // before throwing, on every path that reaches here
                        // (the immediate non-transient failure, and retries
                        // exhausted) — saving again here would just write a
                        // second, less specific error_<guid>.json for the
                        // same failure (see saveErrorFile's collision
                        // handling), so this only needs to log.
                        logSevere(PROCERROR, guid, ioe.getMessage());
                    } catch (InterruptedException ie) {
                        logSevere(PROCERROR, guid, ie.getMessage());
                        Thread.currentThread().interrupt();
                    }
                }, executor);
            }

            logInfo(TASKWAIT);
            executor.shutdown();

            if (!executor.awaitTermination(10, TimeUnit.MINUTES)) {
                logSevere(TASKTOOLONG);
            } else {
                logInfo(TASKSUCCESS);
            }
        }
    }

    /**
     * Submits a single GUID to the Champion API and saves the response.
     * This method implements a retry mechanism for transient errors such as
     * SSL handshake failures, timeouts, any 5xx server error, and — since
     * Champion can also return HTTP 200 while silently failing to evaluate
     * one or more indicators under load — a response body containing an
     * {@link #OVERWHELMED_RESULT_MARKER overwhelmed indicator}, all with
     * exponential backoff between attempts.
     * If all attempts fail, a structured error file is saved with details of the
     * failure.
     *
     * @param guid   full GetRecord URL to submit as the {@code "guid"}
     *               payload field
     * @param index  zero-based position in the batch (for log messages)
     * @param set    set name for error-file naming
     *               (may be {@code null})
     * @param subDir subdirectory under {@code resultsDir} for
     *               results (may be {@code null})
     * @param championUri URI of the FAIR Champion championUri instance to POST to
     * @throws IOException          if the HTTP request or file write
     *                              fails
     * @throws InterruptedException if interrupted awaiting the response
     */
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 2_000;

    /**
     * Substring Champion writes into an indicator's {@code "result"}
     * field — with {@code "log": null} — when it was too overloaded to
     * actually evaluate that indicator, rather than genuinely finding
     * it indeterminate. A normal indeterminate result instead carries
     * a populated {@code "log"} such as
     * {@code "Test result is indeterminate."}. Unlike a 5xx response,
     * this shows up on an otherwise-successful HTTP response, so it
     * has to be detected by inspecting the response body rather than
     * the status code.
     */
    private static final String OVERWHELMED_RESULT_MARKER = "result data not found";

    /**
     * Thrown (as {@code processOneGuid}'s {@code lastException}) once
     * an attempt's response body is found to contain at least one
     * overwhelmed indicator — see {@link #findOverwhelmedIndicatorNames}.
     * If every retry attempt ends up overwhelmed, this is the
     * exception {@link #saveErrorFile} ultimately writes out, and it
     * writes the indicator names as a structured
     * {@code "overwhelmedIndicators"} JSON array rather than requiring
     * downstream analysis to parse them back out of a message string.
     * {@link Class#getSimpleName()} on this type also becomes
     * {@code error_<guid>.json}'s {@code "errorType"} value, so that
     * field alone unambiguously distinguishes this failure kind from
     * a persistent HTTP 5xx response or an SSL/timeout failure (which
     * all otherwise shared the generic {@code IOException}
     * {@code errorType}).
     */
    private static final class OverwhelmedIndicatorException extends IOException {

        private final List<String> indicators;

        OverwhelmedIndicatorException(String guid, List<String> indicators) {
            super("Champion response for GUID " + guid
                    + " contained overwhelmed indicator(s): "
                    + String.join(", ", indicators));
            this.indicators = List.copyOf(indicators);
        }

        List<String> getIndicators() {
            return indicators;
        }
    }

    private void processOneGuid(
            String guid,
            int index,
            String set,
            String subDir,
            String championUri) throws IOException, InterruptedException {

        logInfo("Processing GUID %d: %s", index + 1, guid);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("calculation_uri", spreadsheetUri);
        payload.put("guid", guid);
        String jsonPayload = mapper.writeValueAsString(payload);
        logInfo("%s%s — %s", REQSEND, championUri, jsonPayload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(championUri))
                .header(ACCEPT, HEADER_VALUE)
                .header(CONTENT_TYPE, HEADER_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(requestTimeout)
                .build();

        Exception lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                long backoffMs = INITIAL_BACKOFF_MS * (1L << (attempt - 1)); // 2s, 4s, 8s...
                logInfo("Retry %d/%d for GUID %d after %dms backoff",
                        attempt, MAX_RETRIES - 1, index + 1, backoffMs);
                Thread.sleep(backoffMs);
            }
            try {
                Instant requestStart = Instant.now();
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());
                long elapsedMs = Duration.between(requestStart, Instant.now()).toMillis();

                Path outputDir = resolveOutputDir(subDir);
                Files.createDirectories(outputDir);

                String sanitisedGuid = guid
                        .replaceAll(".*[?&]identifier=([^&]+).*", "$1")
                        .replaceAll("[^a-zA-Z0-9._-]", "_");
                Path jsonOutputPath = outputDir.resolve(sanitisedGuid + ".json");

                if (response.statusCode() >= 500 && response.statusCode() <= 599) {
                    lastException = new IOException(
                            "Server error: HTTP " + response.statusCode());
                    logSevere("Attempt %d failed for GUID %d: HTTP %d",
                            attempt + 1, index + 1, response.statusCode());
                    continue; // trigger next retry iteration
                }

                List<String> overwhelmedIndicators =
                        findOverwhelmedIndicatorNames(response.body());
                if (!overwhelmedIndicators.isEmpty()) {
                    // A dedicated exception type — rather than folding the
                    // names into a generic IOException's message — lets
                    // saveErrorFile write them as a structured
                    // "overwhelmedIndicators" array (and errorType itself
                    // becomes an unambiguous category), instead of
                    // downstream analysis having to regex a free-text
                    // message whose wording can (and has) changed.
                    lastException = new OverwhelmedIndicatorException(
                            guid, overwhelmedIndicators);
                    logSevere("Attempt %d failed for GUID %d: HTTP %d response body"
                                    + " contained %d overwhelmed indicator(s): %s",
                            attempt + 1, index + 1, response.statusCode(),
                            overwhelmedIndicators.size(),
                            String.join(", ", overwhelmedIndicators));
                    continue; // trigger next retry iteration
                }

                writeResponseBodyAsJson(jsonOutputPath, response.body(),
                        guid, response.statusCode());

                logInfo(RESPSAVED + (index + 1)
                        + " (Status: " + response.statusCode()
                        + ", Time: " + elapsedMs + "ms)");
                return; // success — exit retry loop

            } catch (SSLHandshakeException | HttpTimeoutException e) {
                // Transient errors worth retrying
                lastException = e;
                logSevere("Attempt %d failed for GUID %d (%s): %s",
                        attempt + 1, index + 1,
                        e.getClass().getSimpleName(), e.getMessage());
            } catch (IOException e) {
                // Non-transient — fail immediately
                logSevere(PROCFAIL + (index + 1) + ": " + e.getMessage());
                saveErrorFile(guid, set, e, subDir);
                throw e;
            }
        }

        // All retries exhausted
        logSevere(PROCFAIL + (index + 1) + ": all %d attempts failed", MAX_RETRIES);
        saveErrorFile(guid, set, lastException, subDir);
        throw new IOException("All retries exhausted for GUID: " + guid, lastException);
    }

    /**
     * Finds the name(s) of every indicator in a Champion response body
     * whose {@code "result"} field carries
     * {@link #OVERWHELMED_RESULT_MARKER}, i.e. Champion was overloaded
     * and could not actually evaluate that indicator. Non-JSON or
     * unparseable bodies yield an empty list, since
     * {@link #writeResponseBodyAsJson} already handles those by
     * wrapping the raw body rather than expecting indicator objects.
     *
     * @param responseBody raw HTTP response body
     * @return the overwhelmed indicators' names (e.g. {@code "F1_GUID"}),
     *         in encounter order; empty if none (or the body isn't
     *         parseable JSON)
     */
    private List<String> findOverwhelmedIndicatorNames(String responseBody) {
        try {
            List<String> found = new ArrayList<>();
            collectOverwhelmedIndicatorNames(mapper.readTree(responseBody), null, found);
            return found;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Recursively walks a parsed Champion response collecting the
     * name of every indicator object whose {@code "result"} field
     * contains {@link #OVERWHELMED_RESULT_MARKER}. Recursion (rather
     * than assuming indicators sit at the top level) keeps this
     * working across the differently-shaped responses different
     * tenants' algorithms may return.
     *
     * <p>
     * {@code nameHint} carries the most recently descended-through
     * object key — the same approach a downstream Python analysis
     * script independently uses to attribute a match to its test name
     * — so the indicator's own key (e.g. {@code "F1_GUID"}) is what
     * ends up in {@code found}, not one of its ancestors'.
     * </p>
     *
     * @param node     current node being inspected
     * @param nameHint the most recent object key descended through, or
     *                 {@code null} at the root
     * @param found    accumulator for overwhelmed indicator names
     */
    private static void collectOverwhelmedIndicatorNames(
            JsonNode node, String nameHint, List<String> found) {

        if (node == null || !(node.isObject() || node.isArray())) {
            return;
        }
        if (node.isObject()) {
            JsonNode resultNode = node.get("result");
            if (resultNode != null && resultNode.isTextual()
                    && resultNode.asText().contains(OVERWHELMED_RESULT_MARKER)) {
                found.add(nameHint != null ? nameHint : "(unknown)");
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                collectOverwhelmedIndicatorNames(field.getValue(), field.getKey(), found);
            }
        } else {
            // Arrays carry no key of their own — propagate the hint
            // from whichever object key held this array.
            for (JsonNode child : node) {
                collectOverwhelmedIndicatorNames(child, nameHint, found);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Response / error writing
    // -----------------------------------------------------------------------

    /**
     * Writes the raw API response body to a JSON file. If the body is
     * already valid JSON it is written as-is; otherwise it is wrapped
     * in a JSON envelope that captures the GUID, HTTP status code,
     * content type hint, raw content, and a timestamp.
     *
     * @param path         destination file path
     * @param responseBody raw HTTP response body
     * @param guid         the GUID that was processed
     * @param statusCode   HTTP status code returned by the API
     */
    private void writeResponseBodyAsJson(
            Path path,
            String responseBody,
            String guid,
            int statusCode) {

        try {
            String jsonContent;

            try {
                mapper.readTree(responseBody);
                jsonContent = responseBody;
            } catch (Exception e) {
                ObjectNode wrapper = mapper.createObjectNode();
                wrapper.put("guid", guid);
                wrapper.put("statusCode", statusCode);
                wrapper.put("responseType", "html");
                wrapper.put("content", responseBody);
                wrapper.put("timestamp", Instant.now().toString());
                jsonContent = mapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(wrapper);
            }

            Files.write(path,
                    jsonContent.getBytes(StandardCharsets.UTF_8));
            logInfo("\u2713 Saved JSON response for GUID to %s",
                    path.getFileName());

        } catch (IOException e) {
            logSevere(
                    "\u2717 Failed to save JSON file for GUID: %s",
                    e.getMessage());
        }
    }

    /**
     * Saves a structured error description to a JSON file in the
     * appropriate output directory. If a file with the same name
     * already exists, a millisecond timestamp is prepended to avoid
     * overwriting it.
     *
     * @param guid   the GUID that caused the error
     * @param set    set name (may be {@code null})
     * @param error  the exception that was caught
     * @param subDir subdirectory under {@code resultsDir}
     *               (may be {@code null})
     */
    private void saveErrorFile(
            String guid,
            String set,
            Exception error,
            String subDir) {

        try {
            Path outputDir = resolveOutputDir(subDir);
            Files.createDirectories(outputDir);

            String sanitisedGuid = guid
                    .replaceAll("[^a-zA-Z0-9._-]", "_");
            String errorFilename = "error_" + sanitisedGuid + ".json";
            Path errorPath = outputDir.resolve(errorFilename);

            if (Files.exists(errorPath)) {
                errorFilename = "error_"
                        + System.currentTimeMillis()
                        + "_" + errorFilename;
                errorPath = outputDir.resolve(errorFilename);
            }

            ObjectNode errorJson = mapper.createObjectNode();
            errorJson.put("guid", guid);
            errorJson.put("error", error.getMessage());
            errorJson.put("errorType",
                    error.getClass().getSimpleName());
            errorJson.put("timestamp", Instant.now().toString());
            if (error.getCause() != null) {
                errorJson.put("cause",
                        error.getCause().getMessage());
            }
            if (error instanceof OverwhelmedIndicatorException overwhelmed) {
                var indicatorsArray = errorJson.putArray("overwhelmedIndicators");
                for (String indicator : overwhelmed.getIndicators()) {
                    indicatorsArray.add(indicator);
                }
            }

            Files.write(errorPath,
                    mapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(errorJson)
                            .getBytes(StandardCharsets.UTF_8));
            logInfo("\u2713 Saved error details to %s", errorFilename);

        } catch (IOException ioException) {
            logSevere(FILESAVEERR + ioException.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Path helpers
    // -----------------------------------------------------------------------

    /**
     * Resolves the tenant-scoped output directory path for a given
     * subdirectory name, rooted at {@link #resultsDir}.
     *
     * @param subDir subdirectory under {@code resultsDir}, or
     *               {@code null} / blank to use {@code resultsDir} itself
     * @return resolved {@link Path}
     */
    private Path resolveOutputDir(String subDir) {
        return (subDir != null && !subDir.isBlank())
                ? resultsDir.resolve(subDir)
                : resultsDir;
    }

    /**
     * Extracts the set code from a {@code guids_XX.txt}
     * filename.
     *
     * @param filename e.g. {@code "guids_de.txt"} or a full path
     *                 ending with the same pattern
     * @return the set code (e.g. {@code "de"}), or {@code null} if
     *         the filename does not match the expected pattern
     */
    private static String extractSetFromFilename(String filename) {
        String name = Paths.get(filename).getFileName().toString();
        if (name.startsWith("guids_") && name.endsWith(".txt")) {
            return name.substring(6, name.length() - 4);
        }
        return null;
    }

    /**
     * Derives an output subdirectory name from an input filename by
     * stripping the file extension.
     * For example, {@code "guids_de.txt"} → {@code "guids_de"}.
     *
     * @param filename the input filename
     * @return subdirectory name, or {@code "unknown"} if the filename
     *         is null or blank
     */
    private static String deriveSubdirectory(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unknown";
        }
        String name = Paths.get(filename).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // -----------------------------------------------------------------------
    // CLI
    // -----------------------------------------------------------------------

    /**
     * Builds and parses the set of recognised command-line options.
     *
     * @param args raw command-line arguments
     * @return parsed {@link CommandLine}
     * @throws IOException if argument parsing fails
     */
    static CommandLine parseArgs(String[] args) throws IOException {
        Options options = new Options();
        options.addOption("r", "championUri", true,
        "championUri URI (overrides benchmark.runner property)");
        options.addOption("s", SPREADSHEET_ARG, true,
                "spreadsheetUri URI (overrides benchmark.algorithm property)");
        options.addOption("f", FILENAME_ARG, true,
                "GUIDs filename for legacy single-file mode"
                        + " (default: " + DEFAULT_GUIDS_FILE + ")");
        options.addOption("P", PROCESS_ALL_ARG, false,
                "Process all guids_XX.txt files for the default set list");
        options.addOption("p", PROCESS_FILE_ARG, true,
                "Process a single named GUID file");
        options.addOption("g", GUID_ARG, true,
                "Process a single GetRecord URL on the command line");
        options.addOption("t", TENANT_ARG, true,
                "Tenant ID (resolves algorithm/runner and data/results "
                        + "directories from tenants.config.<tenant-id>; "
                        + "an explicit -s/-r still overrides the resolved "
                        + "algorithm/runner). Omit for the shared "
                        + "top-level benchmark.* defaults.");
        options.addOption("h", "help", false, "Show this help message");

        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine cmd = parser.parse(options, args);
            if (cmd.hasOption("h")) {
                new HelpFormatter().printHelp(
                        "java -cp <jar> "
                                + "cessda.cmv.benchmark"
                                + ".RunBenchmarkAssessment",
                        options, true);
                System.exit(0);
            }
            return cmd;
        } catch (ParseException e) {
            logSevere("Error parsing arguments: %s", e.getMessage());
            logSevere("Use -h or --help for usage information.");
            throw new IOException(
                    "Failed to parse command-line arguments", e);
        }
    }

    // -----------------------------------------------------------------------
    // Logging helpers
    // -----------------------------------------------------------------------

    /**
     * Logs a message at {@link Level#INFO} if that level is enabled,
     * formatting it with the supplied arguments when present.
     *
     * @param message message or format string
     * @param args    optional format arguments
     */
    static void logInfo(String message, Object... args) {
        if (logger.isLoggable(Level.INFO)) {
            logger.info(
                    args.length == 0
                            ? message
                            : String.format(message, args));
        }
    }

    /**
     * Logs a message at {@link Level#SEVERE} if that level is enabled,
     * formatting it with the supplied arguments when present.
     *
     * @param message message or format string
     * @param args    optional format arguments
     */
    static void logSevere(String message, Object... args) {
        if (logger.isLoggable(Level.SEVERE)) {
            logger.severe(
                    args.length == 0
                            ? message
                            : String.format(message, args));
        }
    }

    /**
     * Normalises a GUID by checking if it already starts with "http://" or
     * "https://".
     * If it does, it returns the GUID as is. If it does not, it prepends the
     * DEFAULT_OAI_PMH_BASE_URL to the GUID and returns the resulting string.
     *
     * @param guid the GUID to normalise
     * @return the normalised GUID
     */
    private String normaliseGuid(String guid) {
        if (guid.startsWith("http://") || guid.startsWith("https://")) {
            return guid;
        }
        return DEFAULT_OAI_PMH_BASE_URL + guid;
    }

}