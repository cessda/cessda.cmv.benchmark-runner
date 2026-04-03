package cessda.cmv.benchmark;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Client for interacting with the GUID API.
 * This class reads GUIDs from a file, sends them to the API,
 * and saves the responses to files.
 *
 * <p>It also supports fetching lists of identifiers from the CESSDA OAI-PMH
 * endpoint for a set of languages, writing them to per-language resource files,
 * and then processing those files as before.</p>
 *
 * <h2>Command-line modes</h2>
 * <pre>
 *   -F, --fetch-all           Fetch identifiers for all languages from OAI-PMH
 *   -L, --fetch-language <lang>   Fetch identifiers for specified language from OAI-PMH
 *   -P, --process-all         Process all guids_XX.txt files found in resources/current directory
 *   -p, --process-file <file>   Process a single named GUID file
 *   -A, --fetch-and-process   Fetch all identifiers then process all resulting files
 *   -s, --spreadsheet <uri>  Spreadsheet URI (default: BENCHMARK_ALGORITHM_URI)
 *   -f, --filename <file>    Single GUIDs filename (default: guids.txt) - legacy mode
 *   -h, --help               Show help
 * </pre>
 *
 * <p>If none of the new mode flags are specified the original behaviour is preserved
 * (read from {@code -f} file, process it).</p>
 */
public class GuidApiClient {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    static final String BENCHMARK_ALGORITHM_URI =
            "https://tools.ostrails.eu/champion/assess/algorithm/d/1Nk0vM4yBpVQTo_UbB62NY_fz93aRZRHBZGh5fG-khOw";

    static final String GUIDS_FILE = "guids_hr.txt";

    /** OAI-PMH base URL for fetching identifier lists. */
    static final String OAI_PMH_BASE_URL =
            "https://datacatalogue.cessda.eu/oai-pmh/v0/oai";

    /**
     * URL template for retrieving a single DDI 2.5 record via OAI-PMH GetRecord.
     * The identifier is appended as the {@code identifier} query parameter.
     */
    static final String OAI_PMH_GET_RECORD_URL =
            OAI_PMH_BASE_URL + "?verb=GetRecord&metadataPrefix=oai_ddi25&identifier=";

    /** Languages for which identifier lists are fetched. */
    static final String[] LANGUAGES = {"de", "el", "en", "fi", "fr", "hr", "nl", "sl", "sl-SI", "sv"};

    private static final String OUTPUT_DIR = "results";
    private static final String RESOURCES_DIR = "src/main/resources";

    private static final String HEADER_VALUE = "application/json";
    private static final String ACCEPT = "Accept";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String NOGUIDS = "No GUIDs found to process. Exiting.";
    private static final String PROCCOMP = "Processing completed!";
    private static final String FOUNDGUIDS = "Found %d GUID(s) to process";
    private static final String ERROR = "error_";
    private static final String STATUS = " (Status: ";
    private static final String PROCFAIL = "✗ Failed to process GUID ";
    private static final String RESPSAVED = "✓ Saved response for GUID ";
    private static final String PROCERROR = "Error processing GUID %s: %s";
    private static final String TASKWAIT = "Waiting for all tasks to complete...";
    private static final String TASKTOOLONG = "Some tasks did not complete in time!";
    private static final String TASKSUCCESS = "All tasks completed successfully.";
    private static final String REQSEND = "Sending request to API with payload: ";
    private static final String FILESAVEERROR = "Could not save error file: ";
    private static final String FILENAMEARG = "filename";
    private static final String SPREADSHEETARG = "spreadsheet";

    // New CLI option constants
    private static final String FETCH_ALL_ARG = "fetch-all";
    private static final String FETCH_LANGUAGE_ARG = "fetch-language";
    private static final String PROCESS_ALL_ARG = "process-all";
    private static final String PROCESS_FILE_ARG = "process-file";
    private static final String FETCH_AND_PROCESS_ARG = "fetch-and-process";
    private static final String GUID_ARG = "guid";

    static String spreadsheetUri = "";
    static String guidsFilename = "";
    private static Logger logger = Logger.getLogger(GuidApiClient.class.getName());

    private final HttpClient httpClient;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Constructor initializes the HTTP client with a 30-second connection timeout.
     */
    public GuidApiClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    // -----------------------------------------------------------------------
    // main
    // -----------------------------------------------------------------------

    /**
     * Main method to run the GUID processing workflow.
     *
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        logger.setLevel(Level.INFO);

        // Parse args; on failure, exit
        CommandLine cmd;
        try {
            cmd = parseCommandLineArgs(args);
        } catch (IOException e) {
            logSevere("Failed to parse command line arguments: " + e.getMessage());
            return;
        }

        GuidApiClient client = new GuidApiClient();

        try {
            Files.createDirectories(Paths.get(OUTPUT_DIR));

            boolean fetchAll        = cmd.hasOption(FETCH_ALL_ARG);
            boolean fetchLanguage   = cmd.hasOption(FETCH_LANGUAGE_ARG);
            boolean processAll      = cmd.hasOption(PROCESS_ALL_ARG);
            boolean fetchAndProcess = cmd.hasOption(FETCH_AND_PROCESS_ARG);
            boolean processFile     = cmd.hasOption(PROCESS_FILE_ARG);
            boolean singleGuid      = cmd.hasOption(GUID_ARG);

           if (fetchLanguage) {
               String lang = cmd.getOptionValue(FETCH_LANGUAGE_ARG);
               if (lang == null || lang.isBlank()) {
                   logSevere("Language must be specified with -L / --fetch-language");
                   return;
               }
               client.fetchIdentifiersForLanguage(lang);
}
           
            if (fetchAll || fetchAndProcess) {
                // Fetch identifier lists for all languages and write to files
                client.fetchAllLanguageIdentifiers();
            }

            if (processAll || fetchAndProcess) {
                // Process every guids_XX.txt file available
                client.processAllLanguageFiles();
            } else if (processFile) {
                // Process the single file named on the command line
                String filename = cmd.getOptionValue(PROCESS_FILE_ARG);
                client.processSingleFile(filename);
            } else if (singleGuid) {
                // Process a single identifier supplied directly on the command line
                String identifier = cmd.getOptionValue(GUID_ARG);
                client.processSingleGuid(identifier);
            } else if (!fetchAll && !fetchAndProcess) {
                // Legacy behaviour: process the single file specified by -f / default
                List<String> guids = client.readGuidsFromResource();
                if (guids.isEmpty()) {
                    logInfo(NOGUIDS);
                } else {
                    logInfo(String.format(FOUNDGUIDS, guids.size()));
                    client.processGuids(guids, null, false, true);
                    logInfo(PROCCOMP);
                }
            }

        } catch (IOException ioe) {
            logSevere("Error: " + ioe.getMessage());
            ioe.printStackTrace();
        } catch (InterruptedException ie) {
            logSevere("Processing was interrupted: " + ie.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    // -----------------------------------------------------------------------
    // OAI-PMH fetching
    // -----------------------------------------------------------------------

    /**
     * Fetches identifier lists for every language in {@link #LANGUAGES} and
     * writes each list to {@code guids_XX.txt} in the resources directory (or
     * current directory if the resources path does not exist).
     *
     * @throws IOException          if an I/O error occurs
     * @throws InterruptedException if interrupted while waiting for HTTP responses
     */
    public void fetchAllLanguageIdentifiers() throws IOException, InterruptedException {
        logInfo("Starting OAI-PMH identifier fetch for all languages...");
        for (String lang : LANGUAGES) {
            fetchIdentifiersForLanguage(lang);
        }
        logInfo("Finished fetching identifiers for all languages.");
    }

    /**
     * Fetches all identifiers for one language set from the OAI-PMH endpoint,
     * following resumption tokens until the full list has been retrieved, then
     * writes them to {@code guids_<lang>.txt}.
     *
     * @param lang the language code, e.g. {@code "de"}
     * @throws IOException          if an I/O error occurs
     * @throws InterruptedException if interrupted
     */
    public void fetchIdentifiersForLanguage(String lang) throws IOException, InterruptedException {
        logInfo("Fetching identifiers for language: %s", lang);
        List<String> identifiers = new ArrayList<>();

        // Build the initial request URL
        String url = OAI_PMH_BASE_URL
                + "?verb=ListIdentifiers"
                + "&metadataPrefix=oai_dc"
                + "&set=language:" + URLEncoder.encode(lang, StandardCharsets.UTF_8);

        int page = 1;
        while (url != null) {
            logInfo("  Fetching page %d (language=%s): %s", page, lang, url);
            String xml = fetchUrl(url);
            List<String> pageIdentifiers = parseIdentifiers(xml);
            identifiers.addAll(pageIdentifiers);
            logInfo("  Page %d: retrieved %d identifier(s) (total so far: %d)",
                    page, pageIdentifiers.size(), identifiers.size());

            String resumptionToken = parseResumptionToken(xml);
            if (resumptionToken != null && !resumptionToken.isBlank()) {
                // Next page uses the resumptionToken parameter exclusively
                url = OAI_PMH_BASE_URL
                        + "?verb=ListIdentifiers"
                        + "&resumptionToken=" + URLEncoder.encode(resumptionToken, StandardCharsets.UTF_8);
                page++;
            } else {
                url = null; // No more pages
            }
        }

        logInfo("Fetched %d identifier(s) for language: %s", identifiers.size(), lang);
        writeGuidsFile(lang, identifiers);
    }

    /**
     * Performs a simple HTTP GET and returns the response body as a String.
     *
     * @param url the URL to fetch
     * @return response body
     * @throws IOException          if the request fails or returns a non-2xx status
     * @throws InterruptedException if interrupted
     */
    private String fetchUrl(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header(ACCEPT, "application/xml, text/xml, */*")
                .GET()
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " fetching " + url);
        }
        return response.body();
    }

    /**
     * Parses {@code <identifier>} values from OAI-PMH ListIdentifiers XML.
     *
     * @param xml the XML response body
     * @return list of identifier strings
     * @throws IOException if XML parsing fails
     */
    private List<String> parseIdentifiers(String xml) throws IOException {
        List<String> ids = new ArrayList<>();
        try {
            Document doc = parseXml(xml);
            NodeList nodes = doc.getElementsByTagNameNS("*", "identifier");
            // Fall back to no-namespace lookup if namespace-aware search returns nothing
            if (nodes.getLength() == 0) {
                nodes = doc.getElementsByTagName("identifier");
            }
            for (int i = 0; i < nodes.getLength(); i++) {
                String text = nodes.item(i).getTextContent().trim();
                if (!text.isBlank()) {
                    ids.add(text);
                }
            }
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Failed to parse OAI-PMH XML: " + e.getMessage(), e);
        }
        return ids;
    }

    /**
     * Extracts the resumption token text from OAI-PMH XML, or {@code null} if
     * the element is absent or empty.
     *
     * @param xml the XML response body
     * @return resumption token string, or {@code null}
     * @throws IOException if XML parsing fails
     */
    private String parseResumptionToken(String xml) throws IOException {
        try {
            Document doc = parseXml(xml);
            NodeList nodes = doc.getElementsByTagNameNS("*", "resumptionToken");
            if (nodes.getLength() == 0) {
                nodes = doc.getElementsByTagName("resumptionToken");
            }
            if (nodes.getLength() > 0) {
                Element el = (Element) nodes.item(0);
                String token = el.getTextContent().trim();
                return token.isBlank() ? null : token;
            }
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Failed to parse resumption token: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * Parses an XML string into a {@link Document}.
     */
    private Document parseXml(String xml)
            throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // Disable external entity processing for security
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new java.io.StringReader(xml)));
    }

    /**
     * Writes a list of GUIDs to {@code guids_<lang>.txt}.
     * The file is placed in {@value #RESOURCES_DIR} if that directory exists,
     * otherwise in the current working directory.
     *
     * @param lang        language code
     * @param identifiers list of identifier strings to write
     * @throws IOException if the file cannot be written
     */
    private void writeGuidsFile(String lang, List<String> identifiers) throws IOException {
        String filename = "guids_" + lang + ".txt";

        // Prefer the Maven resources directory when running from source; fall back
        // to the current working directory (e.g. when running from a JAR).
        Path resourcesDir = Paths.get(RESOURCES_DIR);
        Path outputPath = Files.isDirectory(resourcesDir)
                ? resourcesDir.resolve(filename)
                : Paths.get(filename);

        List<String> lines = new ArrayList<>();
        lines.add("# Identifiers for language: " + lang);
        lines.add("# Fetched: " + java.time.Instant.now());
        lines.add("# Count: " + identifiers.size());
        lines.addAll(identifiers);

        Files.write(outputPath, lines, StandardCharsets.UTF_8);
        logInfo("✓ Written %d identifier(s) to %s", identifiers.size(), outputPath.toAbsolutePath());
    }

    // -----------------------------------------------------------------------
    // Multi-file processing
    // -----------------------------------------------------------------------

    /**
     * Iterates over every language in {@link #LANGUAGES}, resolves the
     * corresponding {@code guids_XX.txt} file, and processes it if found.
     *
     * @throws IOException          if file operations fail
     * @throws InterruptedException if processing is interrupted
     */
    public void processAllLanguageFiles() throws IOException, InterruptedException {
        logInfo("Processing GUID files for all languages...");
        for (String lang : LANGUAGES) {
            String filename = "guids_" + lang + ".txt";
            logInfo("--- Processing file: %s ---", filename);
            try {
                processSingleFile(filename);
            } catch (FileNotFoundException fnfe) {
                logSevere("Skipping %s - file not found: %s", filename, fnfe.getMessage());
            }
        }
        logInfo("Finished processing all language files.");
    }

    /**
     * Processes a single identifier supplied directly (e.g. from the command line).
     * The identifier is wrapped in a one-element list and passed through the
     * normal processing pipeline so output and error handling are consistent.
     *
     * @param identifier the raw identifier string
     * @throws IOException          if file operations fail
     * @throws InterruptedException if processing is interrupted
     */
    public void processSingleGuid(String guid)
            throws IOException, InterruptedException {
        logInfo("Processing single GUID: %s", guid);
        Files.createDirectories(Paths.get(OUTPUT_DIR));
        processGuidWithMultipleFormats(guid, 0, null, false, true);
        logInfo(PROCCOMP);
    }

    /**
     * Reads GUIDs from the named file and processes them.
     *
     * @param filename the name of the file to read (looked up in resources then CWD)
     * @throws IOException          if file operations fail
     * @throws InterruptedException if processing is interrupted
     */
    public void processSingleFile(String filename) throws IOException, InterruptedException {
        // Temporarily override the global filename so readGuidsFromResource works
        String previousFilename = guidsFilename;
        guidsFilename = filename;
        try {
            List<String> guids = readGuidsFromResource();
            if (guids.isEmpty()) {
                logInfo("No GUIDs found in %s. Skipping.", filename);
                return;
            }
            logInfo(String.format(FOUNDGUIDS, guids.size()) + " in " + filename);
            String lang = extractLangFromFilename(filename);
            String subDir = deriveSubdirectory(filename);
            processGuids(guids, lang, false, true, subDir);
            logInfo(PROCCOMP + " (" + filename + ")");
        } finally {
            guidsFilename = previousFilename;
        }
    }

    // -----------------------------------------------------------------------
    // Getters / helpers
    // -----------------------------------------------------------------------

    /** @return the spreadsheet URI currently in use */
    String getSpreadsheetUri() { return spreadsheetUri; }

    /** @return the GUIDs filename currently in use */
    String getGuidsFilename() { return guidsFilename; }

    /** 
     * @param message
     */
    static void logSevere(String message) {
        if (logger.isLoggable(Level.SEVERE)) logger.severe(message);
    }

    /** 
     * @param message
     * @param args
     */
    static void logSevere(String message, Object... args) {
        if (logger.isLoggable(Level.SEVERE)) logger.severe(String.format(message, args));
    }

    /** 
     * @param message
     */
    static void logInfo(String message) {
        if (logger.isLoggable(Level.INFO)) logger.info(message);
    }

    /** 
     * @param message
     * @param args
     */
    static void logInfo(String message, Object... args) {
        if (logger.isLoggable(Level.INFO)) logger.info(String.format(message, args));
    }

    // -----------------------------------------------------------------------
    // CLI parsing
    // -----------------------------------------------------------------------

    /**
     * Parses command line arguments.
     *
     * @param args raw command line arguments
     * @return the parsed {@link CommandLine}
     * @throws IOException if parsing fails
     */
    static CommandLine parseCommandLineArgs(String[] args) throws IOException {
        Options options = createOptions();
        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine cmd = parser.parse(options, args);
            if (cmd.hasOption("h")) {
                showHelpAndExit(options);
            }
            parseSpreadsheetUri(cmd);
            parseGuidsFilename(cmd);
            return cmd;
        } catch (ParseException e) {
            handleParseError(e);
            throw new IOException("Unreachable"); // handleParseError always throws
        }
    }

    /** 
     * @return Options
     */
    private static Options createOptions() {
        Options options = new Options();
        options.addOption("s", SPREADSHEETARG, true,
                "Spreadsheet URI (default: " + BENCHMARK_ALGORITHM_URI + ")");
        options.addOption("f", FILENAMEARG, true,
                "GUIDs filename (default: guids.txt) - used in legacy single-file mode");
        options.addOption("F", FETCH_ALL_ARG, false,
                "Fetch identifier lists for all languages from OAI-PMH and write guids_XX.txt files");
        options.addOption("L", FETCH_LANGUAGE_ARG, true,
                "Fetch identifiers for specified language from OAI-PMH");
                options.addOption("P", PROCESS_ALL_ARG, false,
                "Process all guids_XX.txt files found in resources / current directory");
        options.addOption("p", PROCESS_FILE_ARG, true,
                "Process a single named GUID file");
        options.addOption("A", FETCH_AND_PROCESS_ARG, false,
                "Fetch all identifier lists and then process all resulting files (equivalent to -F -P)");
        options.addOption("g", GUID_ARG, true,
                "Process a single identifier supplied on the command line");
        options.addOption("h", "help", false, "Show help");
        return options;
    }

    /** 
     * @param options
     */
    private static void showHelpAndExit(Options options) {
        new HelpFormatter().printHelp("java -jar cessda-cmv-benchmark.jar", options, true);
        System.exit(0);
    }

    /** 
     * @param cmd
     */
    private static void parseSpreadsheetUri(CommandLine cmd) {
        if (cmd.hasOption(SPREADSHEETARG)) {
            spreadsheetUri = cmd.getOptionValue(SPREADSHEETARG, BENCHMARK_ALGORITHM_URI);
            logInfo("Using spreadsheet URI from command line: %s", spreadsheetUri);
        } else {
            spreadsheetUri = BENCHMARK_ALGORITHM_URI;
            logInfo("Using default spreadsheet URI: %s", BENCHMARK_ALGORITHM_URI);
        }
    }

    /** 
     * @param cmd
     */
    private static void parseGuidsFilename(CommandLine cmd) {
        if (cmd.hasOption(FILENAMEARG)) {
            guidsFilename = cmd.getOptionValue(FILENAMEARG, GUIDS_FILE);
            logInfo("Using GUIDs filename from command line: %s", guidsFilename);
        } else {
            guidsFilename = GUIDS_FILE;
            logInfo("Using default GUIDs filename: %s", GUIDS_FILE);
        }
    }

    /** 
     * @param e
     * @throws IOException
     */
    private static void handleParseError(ParseException e) throws IOException {
        logSevere("Error parsing arguments: %s", e.getMessage());
        logSevere("Use -h or --help for usage information.");
        throw new IOException("Failed to parse command line arguments", e);
    }

    // -----------------------------------------------------------------------
    // GUID file reading
    // -----------------------------------------------------------------------

    /**
     * Reads GUIDs from the file identified by {@link #guidsFilename}.
     * The classpath (resources) is checked first, then the current directory.
     *
     * @return list of GUID strings
     * @throws IOException if the file cannot be found or read
     */
    private List<String> readGuidsFromResource() throws IOException {
        // Try classpath / resources first
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(guidsFilename)) {
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is,
                        StandardCharsets.UTF_8))) {
                    return reader.lines()
                            .map(String::trim)
                            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                            .toList();
                }
            }
        }

        // Fall back to current directory
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

    /** 
     * @param guid
     * @param index
     * @param lang
     * @param saveAsJson
     * @param saveAsStructuredJson
     * @throws IOException
     * @throws InterruptedException
     */
    // -----------------------------------------------------------------------
    // GUID processing (unchanged logic)
    // -----------------------------------------------------------------------

    private void processGuidWithMultipleFormats(String guid, int index,
            String lang, boolean saveAsJson, boolean saveAsStructuredJson)
            throws IOException, InterruptedException {
        processGuidWithMultipleFormats(guid, index, lang, saveAsJson, saveAsStructuredJson, null);
    }

    /** 
     * @param guid
     * @param index
     * @param lang
     * @param saveAsJson
     * @param saveAsStructuredJson
     * @param subDir               subdirectory inside OUTPUT_DIR for this batch's results (may be null)
     * @throws IOException
     * @throws InterruptedException
     */
    private void processGuidWithMultipleFormats(String guid, int index,
            String lang, boolean saveAsJson, boolean saveAsStructuredJson,
            String subDir)
            throws IOException, InterruptedException {

        logInfo("Processing GUID %d: %s", (index + 1), guid);

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode payload = mapper.createObjectNode();
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

            // Determine output directory: results/<subDir> if subDir is set, else results/
            Path outputDir = (subDir != null && !subDir.isBlank())
                    ? Paths.get(OUTPUT_DIR, subDir)
                    : Paths.get(OUTPUT_DIR);
            Files.createDirectories(outputDir);

            // Name the file after the full (sanitised) GUID identifier.
            //String sanitisedGuid = guid.replaceAll("[^a-zA-Z0-9._-]", "_");
            String sanitisedGuid = guid.replaceAll(".*[?&]identifier=([^&]+).*", "$1");
            Path jsonOutputPath = outputDir.resolve(sanitisedGuid + ".json");
            writeResponseBodyAsJson(jsonOutputPath, response.body(), guid,
                    response.statusCode());

            logInfo(RESPSAVED + (index + 1) +
                    STATUS + response.statusCode() + ", Time: " + processingTimeMs + "ms)");

        } catch (Exception e) {
            logSevere(PROCFAIL + (index + 1) + ": " + e.getMessage());
            saveErrorFile(guid, lang, e, subDir);
            throw e;
        }
    }

    /** 
     * @param guid
     * @param lang
     * @param error
    
    private void saveErrorFile(String guid, String lang, Exception error) {
        saveErrorFile(guid, lang, error, null);
    }
    */

    /** 
     * @param guid
     * @param lang
     * @param error
     * @param subDir subdirectory inside OUTPUT_DIR (may be null)
     */
    private void saveErrorFile(String guid, String lang, Exception error, String subDir) {
        try {
            // Determine output directory
            Path outputDir = (subDir != null && !subDir.isBlank())
                    ? Paths.get(OUTPUT_DIR, subDir)
                    : Paths.get(OUTPUT_DIR);
            Files.createDirectories(outputDir);

            String sanitizedGuid = guid.replaceAll("[^a-zA-Z0-9._-]", "_");
            String errorFilename = ERROR + sanitizedGuid + ".json";
            Path errorPath = outputDir.resolve(errorFilename);

            if (Files.exists(errorPath)) {
                errorFilename = ERROR + System.currentTimeMillis() + "_" + errorFilename;
                errorPath = outputDir.resolve(errorFilename);
            }

            ObjectMapper mapper = new ObjectMapper();
            ObjectNode errorJson = mapper.createObjectNode();
            errorJson.put("guid", guid);
            errorJson.put("error", error.getMessage());
            errorJson.put("errorType", error.getClass().getSimpleName());
            errorJson.put("timestamp", java.time.Instant.now().toString());
            if (error.getCause() != null) {
                errorJson.put("cause", error.getCause().getMessage());
            }

            Files.write(errorPath,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(errorJson)
                            .getBytes(StandardCharsets.UTF_8));
            logInfo("✓ Saved error details to %s", errorFilename);

        } catch (IOException ioException) {
            logSevere(FILESAVEERROR + ioException.getMessage());
        }
    }

    /**
     * Public convenience method to process GUIDs with configurable output formats.
     */
    public void runWithOutputFormats(boolean saveAsJson, boolean saveAsStructuredJson)
            throws IOException, InterruptedException {

        logInfo("Starting GUID processing with custom output formats...");
        Files.createDirectories(Paths.get(OUTPUT_DIR));

        List<String> guids = readGuidsFromResource();
        if (guids.isEmpty()) {
            logInfo(NOGUIDS);
            return;
        }
        logInfo(String.format(FOUNDGUIDS, guids.size()));
        processGuids(guids, null, saveAsJson, saveAsStructuredJson);
        logInfo(PROCCOMP);
    }

    /** 
     * @param guids
     * @param lang
     * @param saveAsJson
     * @param saveAsStructuredJson
     * @throws InterruptedException
     */
    private void processGuids(List<String> guids,
            String lang, boolean saveAsJson, boolean saveAsStructuredJson)
            throws InterruptedException {
        processGuids(guids, lang, saveAsJson, saveAsStructuredJson, null);
    }

    /** 
     * @param guids
     * @param lang
     * @param saveAsJson
     * @param saveAsStructuredJson
     * @param subDir               subdirectory inside OUTPUT_DIR for this batch's results
     * @throws InterruptedException
     */
    private void processGuids(List<String> guids,
            String lang, boolean saveAsJson, boolean saveAsStructuredJson,
            String subDir)
            throws InterruptedException {

        try (ExecutorService executor = Executors.newFixedThreadPool(5)) {
            for (int i = 0; i < guids.size(); i++) {
                final String guid = buildRecordUrl(guids.get(i));
                final int index = i;

                CompletableFuture.runAsync(() -> {
                    try {
                        processGuidWithMultipleFormats(guid, index,
                                lang, saveAsJson, saveAsStructuredJson, subDir);
                    } catch (IOException ioe) {
                        logSevere(String.format(PROCERROR, guid, ioe.getMessage()));
                        saveErrorFile(guid, lang, ioe, subDir);
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
     * @param path
     * @param responseBody
     * @param guid
     * @param statusCode
     */
    // -----------------------------------------------------------------------
    // Response writing helpers
    // -----------------------------------------------------------------------

    private void writeResponseBodyAsJson(Path path, String responseBody,
            String guid, int statusCode) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String jsonContent;
            try {
                mapper.readTree(responseBody);
                jsonContent = responseBody;
            } catch (Exception e) {
                ObjectNode jsonWrapper = mapper.createObjectNode();
                jsonWrapper.put("guid", guid);
                jsonWrapper.put("statusCode", statusCode);
                jsonWrapper.put("responseType", "html");
                jsonWrapper.put("content", responseBody);
                jsonWrapper.put("timestamp", java.time.Instant.now().toString());
                jsonContent = mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(jsonWrapper);
            }
            Files.write(path, jsonContent.getBytes(StandardCharsets.UTF_8));
            logInfo("✓ Saved JSON response for GUID to %s", path.getFileName());
        } catch (IOException e) {
            logSevere("✗ Failed to save JSON file for GUID: %s", e.getMessage());
        }
    }

    /**
     * Builds the OAI-PMH GetRecord URL for the given identifier.
     * The identifier is URL-encoded before being appended.
     *
     * @param identifier the record identifier (plain hash)
     * @return the full GetRecord URL
     */
    private String buildRecordUrl(String identifier) {
        return OAI_PMH_GET_RECORD_URL
                + URLEncoder.encode(identifier, StandardCharsets.UTF_8);
    }

    /**
     * Extracts the language code from a guids_XX.txt filename.
     * Returns {@code null} if the filename does not match the expected pattern.
     *
     * @param filename e.g. {@code "guids_de.txt"} or a full path ending in same
     * @return the language code (e.g. {@code "de"}), or {@code null}
     */
    private static String extractLangFromFilename(String filename) {
        // Accept bare filename or full path — use only the final component
        String name = Paths.get(filename).getFileName().toString();
        if (name.startsWith("guids_") && name.endsWith(".txt")) {
            return name.substring(6, name.length() - 4);
        }
        return null;
    }

    /**
     * Derives the output subdirectory name from an input filename by stripping
     * the {@code .txt} extension (or any extension).  For example,
     * {@code "guids_de.txt"} → {@code "guids_de"}.
     *
     * <p>If the filename has no extension the name is returned unchanged.
     * Only the final path component is considered so full paths are accepted.</p>
     *
     * @param filename the input filename, e.g. {@code "guids_de.txt"}
     * @return subdirectory name, e.g. {@code "guids_de"}
     */
    private static String deriveSubdirectory(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unknown";
        }
        String name = Paths.get(filename).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }


    /**
     * Convenience entry-point (original API kept for compatibility).
     */
    public void run() throws IOException, InterruptedException {
        logInfo("Starting GUID processing workflow...");
        Files.createDirectories(Paths.get(OUTPUT_DIR));
        List<String> guids = readGuidsFromResource();
        if (guids.isEmpty()) {
            logInfo(NOGUIDS);
            return;
        }
        logInfo(String.format(FOUNDGUIDS, guids.size()));
        processGuids(guids, null, false, true);
        logInfo(PROCCOMP);
    }
}