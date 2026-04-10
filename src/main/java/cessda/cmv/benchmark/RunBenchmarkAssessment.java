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
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Reads GUID files produced by {@link GetOaiPmhIdentifiers} (each line is a
 * full OAI-PMH GetRecord URL) and submits every URL to the Champion benchmark
 * assessment API.  One JSON result file is written per GUID into a subdirectory
 * of {@value #OUTPUT_DIR} named after the input file (minus its extension).
 *
 * <h2>Command-line options</h2>
 * <pre>
 *   -s, --spreadsheet <uri>    FAIR Champion API URI to POST GUIDs to
 *                               (default: BENCHMARK_ALGORITHM_URI constant)
 *   -p, --process-file <file>  Process a single named GUID file
 *   -P, --process-all          Process all guids_XX.txt files for the default set list
 *   -g, --guid <url>           Process a single GetRecord URL supplied on the command line
 *   -h, --help                 Show this help message
 * </pre>
 *
 * <p>If none of the mode flags are given the file specified by {@code -f} /
 * {@code --filename} (default: {@value #DEFAULT_GUIDS_FILE}) is processed
 * (legacy single-file mode).</p>
 */
public class RunBenchmarkAssessment {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    static final String BENCHMARK_ALGORITHM_URI =
            "https://tools.ostrails.eu/champion/assess/algorithm/d/1Nk0vM4yBpVQTo_UbB62NY_fz93aRZRHBZGh5fG-khOw";

    static final String DEFAULT_GUIDS_FILE = "guids_hr.txt";

    /** Sets whose {@code guids_XX.txt} files are processed by {@code -P}. */
    static final String[] DEFAULT_SETS =
            {"de", "el", "en", "fi", "fr", "hr", "nl", "sl", "sl-SI", "sv"};

    private static final String OUTPUT_DIR   = "results";

    // String constants shared across methods
    private static final String HEADER_VALUE  = "application/json";
    private static final String ACCEPT        = "Accept";
    private static final String CONTENT_TYPE  = "Content-Type";
    private static final String NOGUIDS       = "No GUIDs found to process. Exiting.";
    private static final String PROCCOMP      = "Processing completed!";
    private static final String FOUNDGUIDS    = "Found %d GUID(s) to process";
    private static final String ERROR         = "error_";
    private static final String STATUS        = " (Status: ";
    private static final String PROCFAIL      = "✗ Failed to process GUID ";
    private static final String RESPSAVED     = "✓ Saved response for GUID ";
    private static final String PROCERROR     = "Error processing GUID %s: %s";
    private static final String TASKWAIT      = "Waiting for all tasks to complete...";
    private static final String TASKTOOLONG   = "Some tasks did not complete in time!";
    private static final String TASKSUCCESS   = "All tasks completed successfully.";
    private static final String REQSEND       = "Sending request to API with payload: ";
    private static final String FILESAVEERROR = "Could not save error file: ";

    // CLI option names
    private static final String SPREADSHEET_ARG  = "spreadsheet";
    private static final String PROCESS_ALL_ARG  = "process-all";
    private static final String PROCESS_FILE_ARG = "process-file";
    private static final String GUID_ARG         = "guid";
    private static final String FILENAME_ARG     = "filename";

    // -----------------------------------------------------------------------
    // Instance state
    // -----------------------------------------------------------------------

    private final HttpClient httpClient;
    private final String spreadsheetUri;

    /**
     * Name of the GUID file currently being processed; may be overridden
     * temporarily by {@link #processSingleFile(String)}.
     */
    private String guidsFilename;

    private static final Logger logger =
            Logger.getLogger(RunBenchmarkAssessment.class.getName());

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Creates a client that posts GUIDs to the supplied Champion API URI.
     *
     * @param spreadsheetUri Champion API URI to POST GUIDs to
     */
    public RunBenchmarkAssessment(String spreadsheetUri) {
        this.spreadsheetUri = spreadsheetUri;
        this.guidsFilename  = DEFAULT_GUIDS_FILE;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    // -----------------------------------------------------------------------
    // main
    // -----------------------------------------------------------------------

    /**
     * Entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        logger.setLevel(Level.INFO);

        CommandLine cmd;
        try {
            cmd = parseArgs(args);
        } catch (IOException e) {
            logSevere("Failed to parse arguments: %s", e.getMessage());
            return;
        }

        String uri = cmd.getOptionValue(SPREADSHEET_ARG, BENCHMARK_ALGORITHM_URI);
        logInfo("Using Champion API URI: %s", uri);

        RunBenchmarkAssessment client = new RunBenchmarkAssessment(uri);

        try {
            Files.createDirectories(Paths.get(OUTPUT_DIR));

            boolean processAll  = cmd.hasOption(PROCESS_ALL_ARG);
            boolean processFile = cmd.hasOption(PROCESS_FILE_ARG);
            boolean singleGuid  = cmd.hasOption(GUID_ARG);

            if (processAll) {
                client.processAllSetFiles();
            } else if (processFile) {
                client.processSingleFile(cmd.getOptionValue(PROCESS_FILE_ARG));
            } else if (singleGuid) {
                client.processSingleGuid(cmd.getOptionValue(GUID_ARG));
            } else {
                // Legacy mode: process the file given by -f / --filename
                if (cmd.hasOption(FILENAME_ARG)) {
                    client.guidsFilename = cmd.getOptionValue(FILENAME_ARG);
                }
                List<String> guids = client.readGuidsFromResource();
                if (guids.isEmpty()) {
                    logInfo(NOGUIDS);
                } else {
                    logInfo(String.format(FOUNDGUIDS, guids.size()));
                    client.processGuids(guids, null, null);
                    logInfo(PROCCOMP);
                }
            }

        } catch (IOException ioe) {
            logSevere("Error: %s", ioe.getMessage());
            ioe.printStackTrace();
        } catch (InterruptedException ie) {
            logSevere("Processing was interrupted: %s", ie.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    // -----------------------------------------------------------------------
    // Multi-file processing
    // -----------------------------------------------------------------------

    /**
     * Iterates over every set in {@link #DEFAULT_SETS}, resolves the
     * corresponding {@code guids_XX.txt} file, and processes it if found.
     *
     * @throws IOException          if file operations fail
     * @throws InterruptedException if processing is interrupted
     */
    public void processAllSetFiles() throws IOException, InterruptedException {
        logInfo("Processing GUID files for all sets...");
        for (String lang : DEFAULT_SETS) {
            String filename = "guids_" + lang + ".txt";
            logInfo("--- Processing file: %s ---", filename);
            try {
                processSingleFile(filename);
            } catch (FileNotFoundException fnfe) {
                logSevere("Skipping %s — file not found: %s", filename, fnfe.getMessage());
            }
        }
        logInfo("Finished processing all set files.");
    }

    /**
     * Reads GUIDs from the named file and processes them.
     *
     * <p>Each non-blank, non-comment line is treated as a full GetRecord URL
     * as produced by {@link GetOaiPmhIdentifiers}.
     *
     * @param filename name of the file to read (looked up in resources then CWD)
     * @throws IOException          if file operations fail
     * @throws InterruptedException if processing is interrupted
     */
    public void processSingleFile(String filename) throws IOException, InterruptedException {
        String previousFilename = guidsFilename;
        guidsFilename = filename;
        try {
            List<String> guids = readGuidsFromResource();
            if (guids.isEmpty()) {
                logInfo("No GUIDs found in %s. Skipping.", filename);
                return;
            }
            logInfo(String.format(FOUNDGUIDS, guids.size()) + " in " + filename);
            String lang   = extractLangFromFilename(filename);
            String subDir = deriveSubdirectory(filename);
            processGuids(guids, lang, subDir);
            logInfo(PROCCOMP + " (" + filename + ")");
        } finally {
            guidsFilename = previousFilename;
        }
    }

    /**
     * Processes a single GetRecord URL supplied directly on the command line.
     *
     * @param guid the full GetRecord URL
     * @throws IOException          if file operations fail
     * @throws InterruptedException if processing is interrupted
     */
    public void processSingleGuid(String guid) throws IOException, InterruptedException {
        logInfo("Processing single GUID: %s", guid);
        Files.createDirectories(Paths.get(OUTPUT_DIR));
        processOneGuid(guid, 0, null, null);
        logInfo(PROCCOMP);
    }

    // -----------------------------------------------------------------------
    // GUID file reading
    // -----------------------------------------------------------------------

    /**
     * Reads GUIDs from the file identified by {@link #guidsFilename}.
     * The classpath (resources) is checked first, then the current directory.
     *
     * @return list of GUID / GetRecord URL strings
     * @throws IOException if the file cannot be found or read
     */
    private List<String> readGuidsFromResource() throws IOException {
        try (InputStream is =
                getClass().getClassLoader().getResourceAsStream(guidsFilename)) {
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    return reader.lines()
                            .map(String::trim)
                            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                            .toList();
                }
            }
        }

        Path guidsPath = Paths.get(guidsFilename);
        if (Files.exists(guidsPath)) {
            return Files.readAllLines(guidsPath, StandardCharsets.UTF_8)
                    .stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        }

        throw new FileNotFoundException(
                "Could not find " + guidsFilename + " in resources or current directory");
    }

    // -----------------------------------------------------------------------
    // GUID processing
    // -----------------------------------------------------------------------

    /**
     * Submits all GUIDs to the Champion API using a fixed thread pool of 5.
     *
     * @param guids  list of GetRecord URLs to process
     * @param set   set name(used only for error-file naming; may be null)
     * @param subDir subdirectory inside {@value #OUTPUT_DIR} for results (may be null)
     * @throws InterruptedException if the executor is interrupted while waiting
     */
    private void processGuids(List<String> guids, String set, String subDir)
            throws InterruptedException {

        try (ExecutorService executor = Executors.newFixedThreadPool(5)) {
            for (int i = 0; i < guids.size(); i++) {
                final String guid  = guids.get(i);
                final int    index = i;

                CompletableFuture.runAsync(() -> {
                    try {
                        processOneGuid(guid, index, set, subDir);
                    } catch (IOException ioe) {
                        logSevere(String.format(PROCERROR, guid, ioe.getMessage()));
                        saveErrorFile(guid, set, ioe, subDir);
                    } catch (InterruptedException ie) {
                        logSevere(String.format(PROCERROR, guid, ie.getMessage()));
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
     * Submits a single GetRecord URL to the Champion API and writes the result.
     *
     * <p>The GUID file now contains full GetRecord URLs, so they are used
     * directly as the {@code "guid"} field in the JSON payload — no further
     * URL construction is needed.
     *
     * @param guid   full GetRecord URL
     * @param index  zero-based position in the batch (for log messages)
     * @param set    set name (used only for error-file naming; may be null)
     * @param subDir subdirectory inside {@value #OUTPUT_DIR} for results (may be null)
     * @throws IOException          if the HTTP request or file write fails
     * @throws InterruptedException if interrupted while waiting for the response
     */
    private void processOneGuid(String guid, int index, String set, String subDir)
            throws IOException, InterruptedException {

        logInfo("Processing GUID %d: %s", (index + 1), guid);

        ObjectMapper mapper  = new ObjectMapper();
        ObjectNode   payload = mapper.createObjectNode();
        payload.put("guid", guid);
        payload.put("url", spreadsheetUri);
        String jsonPayload = mapper.writeValueAsString(payload);
        logInfo(REQSEND + jsonPayload);

        java.time.Instant requestStart = java.time.Instant.now();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(spreadsheetUri))
                .header(ACCEPT, HEADER_VALUE)
                .header(CONTENT_TYPE, HEADER_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(Duration.ofSeconds(60))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            long processingTimeMs = Duration.between(requestStart,
                    java.time.Instant.now()).toMillis();

            Path outputDir = resolveOutputDir(subDir);
            Files.createDirectories(outputDir);

            // Derive the output filename from the identifier portion of the URL.
            // If the URL contains an "identifier=" parameter, use its value;
            // otherwise sanitise the whole URL.
            String sanitisedGuid = guid.replaceAll(".*[?&]identifier=([^&]+).*", "$1")
                                       .replaceAll("[^a-zA-Z0-9._-]", "_");
            Path jsonOutputPath = outputDir.resolve(sanitisedGuid + ".json");

            writeResponseBodyAsJson(jsonOutputPath, response.body(), guid,
                    response.statusCode());

            logInfo(RESPSAVED + (index + 1) +
                    STATUS + response.statusCode() + ", Time: " + processingTimeMs + "ms)");

        } catch (Exception e) {
            logSevere(PROCFAIL + (index + 1) + ": " + e.getMessage());
            saveErrorFile(guid, set, e, subDir);
            throw e;
        }
    }

    // -----------------------------------------------------------------------
    // Response / error writing
    // -----------------------------------------------------------------------

    /**
     * Writes the raw API response to a JSON file.  If the body is already valid
     * JSON it is written as-is; otherwise it is wrapped in a JSON envelope.
     *
     * @param path         destination file path
     * @param responseBody raw HTTP response body
     * @param guid         the GUID that was processed
     * @param statusCode   HTTP status code returned by the API
     */
    private void writeResponseBodyAsJson(Path path, String responseBody,
            String guid, int statusCode) {
        try {
            ObjectMapper mapper = new ObjectMapper();
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
                wrapper.put("timestamp", java.time.Instant.now().toString());
                jsonContent = mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(wrapper);
            }
            Files.write(path, jsonContent.getBytes(StandardCharsets.UTF_8));
            logInfo("✓ Saved JSON response for GUID to %s", path.getFileName());
        } catch (IOException e) {
            logSevere("✗ Failed to save JSON file for GUID: %s", e.getMessage());
        }
    }

    /**
     * Saves an error description to a JSON file in the appropriate output directory.
     *
     * @param guid   the GUID that caused the error
     * @param set    set name (may be null)
     * @param error  the exception that was caught
     * @param subDir subdirectory inside {@value #OUTPUT_DIR} (may be null)
     */
    private void saveErrorFile(String guid, String set, Exception error, String subDir) {
        try {
            Path outputDir = resolveOutputDir(subDir);
            Files.createDirectories(outputDir);

            String sanitizedGuid  = guid.replaceAll("[^a-zA-Z0-9._-]", "_");
            String errorFilename  = ERROR + sanitizedGuid + ".json";
            Path   errorPath      = outputDir.resolve(errorFilename);

            if (Files.exists(errorPath)) {
                errorFilename = ERROR + System.currentTimeMillis() + "_" + errorFilename;
                errorPath     = outputDir.resolve(errorFilename);
            }

            ObjectMapper mapper    = new ObjectMapper();
            ObjectNode   errorJson = mapper.createObjectNode();
            errorJson.put("guid",      guid);
            errorJson.put("error",     error.getMessage());
            errorJson.put("errorType", error.getClass().getSimpleName());
            errorJson.put("timestamp", java.time.Instant.now().toString());
            if (error.getCause() != null) {
                errorJson.put("cause", error.getCause().getMessage());
            }

            Files.write(errorPath,
                    mapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(errorJson)
                            .getBytes(StandardCharsets.UTF_8));
            logInfo("✓ Saved error details to %s", errorFilename);

        } catch (IOException ioException) {
            logSevere(FILESAVEERROR + ioException.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Path helpers
    // -----------------------------------------------------------------------

    /**
     * Resolves the output directory for a given subdirectory name.
     *
     * @param subDir subdirectory inside {@value #OUTPUT_DIR}, or {@code null}
     * @return resolved {@link Path}
     */
    private static Path resolveOutputDir(String subDir) {
        return (subDir != null && !subDir.isBlank())
                ? Paths.get(OUTPUT_DIR, subDir)
                : Paths.get(OUTPUT_DIR);
    }

    /**
     * Extracts the set name from a {@code guids_XX.txt} filename.
     *
     * @param filename e.g. {@code "guids_de.txt"} or a full path ending in same
     * @return the set name (e.g. {@code "de"}), or {@code null}
     */
    private static String extractLangFromFilename(String filename) {
        String name = Paths.get(filename).getFileName().toString();
        if (name.startsWith("guids_") && name.endsWith(".txt")) {
            return name.substring(6, name.length() - 4);
        }
        return null;
    }

    /**
     * Derives an output subdirectory name from an input filename by stripping
     * the file extension.  For example, {@code "guids_de.txt"} → {@code "guids_de"}.
     *
     * @param filename the input filename
     * @return subdirectory name
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
     * Builds and parses the command-line options.
     *
     * @param args raw command-line arguments
     * @return parsed {@link CommandLine}
     * @throws IOException if argument parsing fails
     */
    static CommandLine parseArgs(String[] args) throws IOException {
        Options options = new Options();
        options.addOption("s", SPREADSHEET_ARG, true,
                "Champion API URI (default: " + BENCHMARK_ALGORITHM_URI + ")");
        options.addOption("f", FILENAME_ARG, true,
                "GUIDs filename for legacy single-file mode (default: " + DEFAULT_GUIDS_FILE + ")");
        options.addOption("P", PROCESS_ALL_ARG, false,
                "Process all guids_XX.txt files for the default set list");
        options.addOption("p", PROCESS_FILE_ARG, true,
                "Process a single named GUID file");
        options.addOption("g", GUID_ARG, true,
                "Process a single GetRecord URL supplied on the command line");
        options.addOption("h", "help", false, "Show help");

        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine cmd = parser.parse(options, args);
            if (cmd.hasOption("h")) {
                new HelpFormatter().printHelp(
                        "java -cp <jar> cessda.cmv.benchmark.RunBenchmarkAssessment", options, true);
                System.exit(0);
            }
            return cmd;
        } catch (ParseException e) {
            logSevere("Error parsing arguments: %s", e.getMessage());
            logSevere("Use -h or --help for usage information.");
            throw new IOException("Failed to parse command-line arguments", e);
        }
    }

    /** 
     * @param message
     * @param args
     */
    // -----------------------------------------------------------------------
    // Logging helpers
    // -----------------------------------------------------------------------

    static void logInfo(String message, Object... args) {
        if (logger.isLoggable(Level.INFO)) {
            logger.info(args.length == 0 ? message : String.format(message, args));
        }
    }

    /** 
     * @param message
     * @param args
     */
    static void logSevere(String message, Object... args) {
        if (logger.isLoggable(Level.SEVERE)) {
            logger.severe(args.length == 0 ? message : String.format(message, args));
        }
    }
}