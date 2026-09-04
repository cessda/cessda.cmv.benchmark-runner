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

import cessda.cmv.benchmark.config.BenchmarkProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.cli.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

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
    static final String CHAMPION_URI_ARG = "championUri";
    private static final String FOUNDGUIDS = "Found {0} GUID(s) to process";
    private static final String TASKTOOLONG = "Some tasks did not complete in time!";
    private static final String TASKSUCCESS = "All tasks completed successfully.";
    private static final String REQSEND = "Sending request to ";
    private static final String FILESAVEERR = "Could not save error file: ";
    private static final String PROCERROR = "Error processing GUID {0}: {1}";
    private static final String PROCFAIL = "Failed to process GUID ";

    // CLI option names
    private static final String SPREADSHEET_ARG = "spreadsheetUri";
    private static final String PROCESS_ALL_ARG = "process-all";
    private static final String PROCESS_FILE_ARG = "process-file";
    private static final String GUID_ARG = "guid";
    private static final String FILENAME_ARG = "filename";
    private static final String RESPSAVED = "Saved response for GUID ";

    private final Duration requestTimeout;

    private static final Logger logger = Logger.getLogger(RunBenchmarkAssessment.class.getName());

    // -----------------------------------------------------------------------
    // Instance state
    // -----------------------------------------------------------------------
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 2_000;
    private final Path dataDir;

    private final HttpClient httpClient;
    private final Path resultsDir;
    /**
     * URI of the benchmark assessment spreadsheetUri, supplied by the
     * caller (e.g. resolved per-tenant by {@code BenchmarkService}, or
     * read from {@code application.yml} / CLI flags in standalone
     * mode via {@link #main(String[])}).
     */
    private URI spreadsheetUri;


    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------
    /**
     * URI of the FAIR Champion championUri instance, supplied by the
     * caller in the same way as {@link #spreadsheetUri}.
     */
    private URI championUri;
    /**
     * Name of the GUID file currently being processed; may be
     * overridden temporarily by {@link #processSingleFile(Path)}.
     */
    private Path guidsFilename;

    private RunBenchmarkAssessment(
            Duration requestTimeout,
            URI spreadsheetUri,
            URI championUri,
            Path guidsFilename,
            HttpClient httpClient,
            Path dataDir,
            Path resultsDir
    ) {
        this.requestTimeout = requestTimeout;
        this.spreadsheetUri = spreadsheetUri;
        this.championUri = championUri;
        this.guidsFilename = guidsFilename;
        this.httpClient = httpClient;
        this.dataDir = dataDir;
        this.resultsDir = resultsDir;
    }

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
     * @param requestTimeout     HTTP request timeout, in seconds
     */
    public RunBenchmarkAssessment(
            URI spreadsheetUri,
            URI championUri,
            Duration requestTimeout,
            HttpClient httpClient
    ) {
        this(
                requestTimeout,
                spreadsheetUri,
                championUri,
                Path.of(DEFAULT_GUIDS_FILE),
                httpClient,
                Paths.get("data"),
                Paths.get(OUTPUT_DIR)
        );
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

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
    public RunBenchmarkAssessment(
            URI spreadsheetUri,
            URI championUri,
            Path dataDir,
            Path resultsDir
    ) {
        this(
                Duration.ofSeconds(120),
                spreadsheetUri,
                championUri,
                Path.of(DEFAULT_GUIDS_FILE),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                dataDir,
                resultsDir
        );
    }

    /**
     * Convenience constructor for direct instantiation outside of a
     * Spring context. Uses default timeout values of 30s (connect)
     * and 120s (request), and default paths of {@code data/} and
     * {@code results/} for the data and results directories.
     *
     * <p>Use {@link #RunBenchmarkAssessment(URI, URI, Path, Path)}
     * when tenant-scoped directory isolation is required.</p>
     *
     * @param spreadsheetUri URI of the benchmark assessment spreadsheetUri
     * @param championUri    URI of the FAIR Champion championUri
     */
    public RunBenchmarkAssessment(
            URI spreadsheetUri,
            URI championUri
    ) {
        this(
                spreadsheetUri,
                championUri,
                Duration.ofSeconds(120),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
        );
    }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

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
     * This standalone entry point uses the shared top-level
     * {@code benchmark.algorithm} / {@code benchmark.runner}
     * properties, not any tenant-specific {@code tenants.config}
     * entry — the CLI has no concept of "the current tenant". For
     * per-tenant runs, use the REST API
     * ({@code POST /api/run-assessment} with a tenant's
     * {@code X-API-Key}) instead.
     * </p>
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) throws IOException, InterruptedException {
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
        } catch (ParseException e) {
            System.out.printf("Error parsing arguments: %s%nUse -h or --help for usage information.", e.getMessage());
            System.exit(-1);
            return;
        }

        // Allow the CLI to override the injected championUri URI.
        if (cmd.hasOption(CHAMPION_URI_ARG)) {
            client.championUri = URI.create(cmd.getOptionValue(CHAMPION_URI_ARG));
        }

        // Allow the CLI to override the injected spreadsheetUri URI.
        if (cmd.hasOption(SPREADSHEET_ARG)) {
            client.spreadsheetUri = URI.create(cmd.getOptionValue(SPREADSHEET_ARG));
        }

        if (client.spreadsheetUri == null) {
            logger.log(Level.SEVERE, "No benchmark.algorithm configured and no --{0} override supplied.", SPREADSHEET_ARG);
            return;
        }
        if (client.championUri == null) {
            logger.log(Level.SEVERE, "No benchmark.runner configured and no --championUri override supplied.");
            return;
        }
        logger.log(Level.INFO, "Using spreadsheetUri URI: {0}", client.spreadsheetUri);
        logger.log(Level.INFO, "Using championUri URI: {0}", client.championUri);

        Files.createDirectories(client.resultsDir);

        boolean processAll = cmd.hasOption(PROCESS_ALL_ARG);
        boolean processFile = cmd.hasOption(PROCESS_FILE_ARG);
        boolean singleGuid = cmd.hasOption(GUID_ARG);

        if (processAll) {
            client.processAllSetFiles();
        } else if (processFile) {
            client.processSingleFile(Path.of(cmd.getOptionValue(PROCESS_FILE_ARG)));
        } else if (singleGuid) {
            client.processSingleGuid(cmd.getOptionValue(GUID_ARG));
        } else {
            // Legacy mode: process the file given by -f/--filename.
            if (cmd.hasOption(FILENAME_ARG)) {
                client.guidsFilename = Path.of(cmd.getOptionValue(FILENAME_ARG));
            }
            List<String> guids = client.readGuidsFromResource();
            if (guids.isEmpty()) {
                logger.info(NOGUIDS);
            } else {
                logger.log(Level.INFO, FOUNDGUIDS, guids.size());
                client.processGuids(guids, null);
                logger.info(PROCCOMP);
            }
        }
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
    private static Path deriveSubdirectory(Path filename) {
        String name = filename.getFileName().toString();
        int dot = name.lastIndexOf('.');
        var string = dot > 0 ? name.substring(0, dot) : name;
        return Path.of(string);
    }

    // -----------------------------------------------------------------------
    // Multi-file processing
    // -----------------------------------------------------------------------

    /**
     * Builds and parses the set of recognised command-line options.
     *
     * @param args raw command-line arguments
     * @return parsed {@link CommandLine}
     * @throws ParseException if argument parsing fails
     */
    static CommandLine parseArgs(String[] args) throws ParseException {
        Options options = new Options();
        options.addOption("r", CHAMPION_URI_ARG, true,
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
        options.addOption("h", "help", false, "Show this help message");

        CommandLineParser parser = new DefaultParser();

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
    }

    /**
     * Returns the URI of the benchmark assessment spreadsheetUri.
     * This value is supplied by the caller — either resolved per
     * tenant by {@code BenchmarkService}, or read from
     * {@code application.yml} / overridden via the {@code -s} CLI
     * flag in standalone mode.
     *
     * @return the spreadsheetUri URI string
     */
    public URI getSpreadsheetUri() {
        return spreadsheetUri;
    }

    /**
     * Returns the URI of the FAIR Champion championUri instance.
     * This value is supplied by the caller in the same way as
     * {@link #getSpreadsheetUri()}.
     *
     * @return the championUri URI string
     */
    public URI getChampionUri() {
        return championUri;
    }

    // -----------------------------------------------------------------------
    // GUID file reading
    // -----------------------------------------------------------------------

    /**
     * Iterates over every set in {@link #DEFAULT_SETS}, resolves the
     * corresponding {@code guids_XX.txt} file, and processes it if
     * found. Missing files are logged and skipped rather than causing
     * a hard failure.
     *
     * @throws InterruptedException if processing is interrupted
     */
    public void processAllSetFiles() throws InterruptedException {

        logger.info("Processing GUID files for all sets...");
        for (String set : DEFAULT_SETS) {
            Path filename = Path.of("guids_" + set + ".txt");
            logger.log(Level.INFO, "Processing file: {0}", filename);
            try {
                processSingleFile(filename);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Skipping {0} — : {1}", new Object[]{filename, e.toString()});
            }
        }
        logger.info("Finished processing all set files.");
    }

    // -----------------------------------------------------------------------
    // GUID processing
    // -----------------------------------------------------------------------

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
    public void processSingleFile(Path filename)
            throws IOException, InterruptedException {

        Path previousFilename = guidsFilename;
        guidsFilename = filename;
        try {
            List<String> guids = readGuidsFromResource();
            if (guids.isEmpty()) {
                logger.log(Level.INFO, "No GUIDs found in {0}. Skipping.", filename);
                return;
            }
            logger.log(Level.INFO, FOUNDGUIDS + " in {1}", new Object[]{guids.size(), filename});
            Path subDir = deriveSubdirectory(filename);
            processGuids(guids, subDir);
            logger.log(Level.INFO, PROCCOMP + " ({0})", filename);
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

        logger.log(Level.INFO, "Processing single GUID: {0}", guid);
        processOneGuid(guid, null);
        logger.info(PROCCOMP);
    }

    /**
     * Reads GUIDs from the file identified by {@link #guidsFilename}.
     * The classpath (resources) is checked first, then the path as
     * given, then the configured {@link #dataDir}.
     *
     * @return an immutable list of GUID / GetRecord URL strings
     * @throws IOException if the file cannot be found or read
     */
    private List<String> readGuidsFromResource() throws IOException {
        return Files.readAllLines(guidsFilename, StandardCharsets.UTF_8)
                .stream()
                .map(String::trim)
                .filter(l -> !l.isBlank() && !l.startsWith("#"))
                .toList();
    }

    /**
     * Submits all GUIDs to the Champion API using a fixed thread pool
     * of five workers and awaits completion for up to ten minutes.
     *
     * @param guids  list of GetRecord URLs to submit
     * @param subDir subdirectory under {@code resultsDir} for
     *               results (may be {@code null})
     * @throws InterruptedException if the executor is interrupted
     *                              while waiting
     */
    private void processGuids(
            List<String> guids,
            Path subDir) throws InterruptedException {

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            for (String rawGuid : guids) {
                final String guid = normaliseGuid(rawGuid);

                CompletableFuture.runAsync(() -> {
                    try {
                        processOneGuid(guid, subDir);
                    } catch (IOException ioe) {
                        logger.log(Level.SEVERE, PROCERROR, new Object[]{guid, ioe.toString()});
                        saveErrorFile(guid, ioe, subDir);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }, executor);
            }

            executor.shutdown();

            if (executor.awaitTermination(10, TimeUnit.MINUTES)) {
                logger.info(TASKSUCCESS);
            } else {
                logger.warning(TASKTOOLONG);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Response / error writing
    // -----------------------------------------------------------------------

    /**
     * Submits a single GUID to the Champion API and saves the response.
     * This method implements a retry mechanism for transient errors such as
     * SSL handshake failures and timeouts, with exponential backoff between
     * attempts.
     * If all attempts fail, a structured error file is saved with details of the
     * failure.
     *
     * @param guid   full GetRecord URL to submit as the {@code "guid"}
     *               payload field
     * @param subDir subdirectory under {@code resultsDir} for
     *               results (may be {@code null})
     * @throws IOException          if the HTTP request or file write
     *                              fails
     * @throws InterruptedException if interrupted awaiting the response
     */
    private void processOneGuid(
            String guid,
            Path subDir) throws IOException, InterruptedException {

        logger.log(Level.INFO, "Processing GUID {}", guid);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("calculation_uri", spreadsheetUri.toString());
        payload.put("guid", guid);
        String jsonPayload = mapper.writeValueAsString(payload);
        logger.log(Level.INFO, "{}{} — {}", new Object[]{REQSEND, championUri, jsonPayload});

        HttpRequest request = HttpRequest.newBuilder()
                .uri(championUri)
                .header(ACCEPT, HEADER_VALUE)
                .header(CONTENT_TYPE, HEADER_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(requestTimeout)
                .build();

        Exception lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                long backoffMs = INITIAL_BACKOFF_MS * (1L << (attempt - 1)); // 2s, 4s, 8s...
                logger.log(Level.INFO, "Retry {0}/{1} for GUID {2} after {3}ms backoff",
                        new Object[]{attempt, MAX_RETRIES - 1, guid, backoffMs}
                );
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

                if (response.statusCode() == 504 || response.statusCode() == 502) {
                    lastException = new IOException(
                            "Gateway error: HTTP " + response.statusCode());
                    logger.log(Level.FINE, "Attempt {} failed for GUID {}: HTTP {}",
                            new Object[]{attempt + 1, guid, response.statusCode()});
                    continue; // trigger next retry iteration
                }

                writeResponseBodyAsJson(jsonOutputPath, response.body(), guid, response.statusCode());

                logger.info(RESPSAVED + guid + " (Status: " + response.statusCode() + ", Time: " + elapsedMs + "ms)");
                return; // success — exit retry loop

            } catch (HttpTimeoutException e) {
                // Transient errors worth retrying
                lastException = e;
                logger.log(Level.FINE, "Attempt {0} failed for GUID {1}: {2}",
                        new Object[]{attempt + 1, guid, e.toString()});
            } catch (IOException e) {
                // Non-transient — fail immediately
                logger.log(Level.SEVERE, PROCFAIL + "{0}: {1}", new Object[]{guid, e.getMessage()});
                saveErrorFile(guid, e, subDir);
                throw e;
            }
        }

        // All retries exhausted
        logger.log(Level.SEVERE, PROCFAIL + "{0}: all {1} attempts failed", new Object[]{guid, MAX_RETRIES});
        saveErrorFile(guid, lastException, subDir);
        throw new IOException("All retries exhausted for GUID: " + guid, lastException);
    }

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
            } catch (JsonProcessingException e) {
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

            Files.writeString(path, jsonContent);
            logger.log(Level.INFO, "Saved JSON response for GUID to {0}", path.getFileName());

        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save JSON file for GUID: {0}", e.toString());
        }
    }

    // -----------------------------------------------------------------------
    // Path helpers
    // -----------------------------------------------------------------------

    /**
     * Saves a structured error description to a JSON file in the
     * appropriate output directory. If a file with the same name
     * already exists, a millisecond timestamp is prepended to avoid
     * overwriting it.
     *
     * @param guid   the GUID that caused the error
     * @param error  the exception that was caught
     * @param subDir subdirectory under {@code resultsDir}
     *               (may be {@code null})
     */
    private void saveErrorFile(
            String guid,
            Exception error,
            Path subDir) {

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

            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(errorPath.toFile(), errorJson);

            logger.log(Level.INFO, "Saved error details to {0}", errorFilename);

        } catch (IOException e) {
            logger.log(Level.SEVERE, FILESAVEERR + " {0}", e.getMessage());
        }
    }

    /**
     * Resolves the tenant-scoped output directory path for a given
     * subdirectory name, rooted at {@link #resultsDir}.
     *
     * @param subDir subdirectory under {@code resultsDir}, or
     *               {@code null} / blank to use {@code resultsDir} itself
     * @return resolved {@link Path}
     */
    private Path resolveOutputDir(Path subDir) {
        if (subDir == null) {
            return resultsDir;
        } else if (!subDir.isAbsolute()) {
            return resultsDir.resolve(subDir);
        } else {
            throw new IllegalArgumentException("subDir must be a relative path");
        }
    }

    // -----------------------------------------------------------------------
    // CLI
    // -----------------------------------------------------------------------

    /**
     * Spring configuration used only by {@link #main(String[])} to
     * load {@code application.yml} and bind the shared
     * {@code benchmark.algorithm} / {@code benchmark.runner}
     * properties for standalone command-line use.
     *
     * <p>
     * This is deliberately separate from {@link RunBenchmarkAssessment}
     * itself, which is never a Spring bean — see the class-level
     * Javadoc for why a shared singleton is incompatible with
     * multi-tenancy. Standalone CLI use has no concept of "the current
     * tenant", so it falls back to the shared top-level
     * {@code benchmark.algorithm} / {@code benchmark.runner}
     * properties rather than any {@code tenants.config} entry. Both
     * properties default to an empty string if absent, rather than
     * failing context startup, since a CLI user may always supply
     * {@code -s} / {@code -r} instead.
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
     * since this configuration declares its one {@code @Bean} method
     * directly.
     * </p>
     */
    @Configuration
    @EnableConfigurationProperties(BenchmarkProperties.class)
    @EnableAutoConfiguration
    static class CliConfig {

        @Bean
        RunBenchmarkAssessment runBenchmarkAssessment(
                BenchmarkProperties benchmarkProperties) {
            return new RunBenchmarkAssessment(
                    benchmarkProperties.getAlgorithm(),
                    benchmarkProperties.getRunner());
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