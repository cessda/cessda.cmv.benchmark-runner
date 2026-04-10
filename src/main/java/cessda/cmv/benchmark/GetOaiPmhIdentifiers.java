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

import java.io.IOException;
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

/**
 * Fetches identifier lists from an OAI-PMH endpoint and writes them as full
 * GetRecord URLs to {@code guids_<set>.txt} files.
 *
 * <p>Each output line is a complete, ready-to-use OAI-PMH GetRecord URL, e.g.:
 * <pre>
 *   https://datacatalogue.cessda.eu/oai-pmh/v0/oai?verb=GetRecord&amp;metadataPrefix=oai_ddi25&amp;identifier=abc123
 * </pre>
 *
 * <h2>Command-line options</h2>
 * <pre>
 *   -b, --oai-pmh-base-url <url>      OAI-PMH base URL
 *                                       (default: https://datacatalogue.cessda.eu/oai-pmh/v0/oai)
 *   -v, --verb <verb>                  OAI-PMH verb used when listing identifiers
 *                                       (default: ListIdentifiers)
 *   -m, --metadata-prefix <prefix>    Metadata prefix embedded in output GetRecord URLs
 *                                       (default: oai_ddi25)
 *   -S, --sets <set1,set2,...>         Comma-separated list of sets to fetch
 *                                       (default: de,el,en,fi,fr,hr,nl,sl,sl-SI,sv)
 *   -F, --fetch-all-sets               Fetch identifiers for all sets (default behaviour)
 *   -s, --fetch-set <set>              Fetch identifiers for a single set only
 *   -h, --help                         Show this help message
 * </pre>
 */
public class GetOaiPmhIdentifiers {

    // -----------------------------------------------------------------------
    // Defaults
    // -----------------------------------------------------------------------

    static final String DEFAULT_OAI_PMH_BASE_URL =
            "https://datacatalogue.cessda.eu/oai-pmh/v0/oai";

    static final String DEFAULT_VERB = "ListIdentifiers";

    /** Metadata prefix used when constructing the output GetRecord URLs. */
    static final String DEFAULT_METADATA_PREFIX = "oai_ddi25";

    static final String[] DEFAULT_SETS =
            {"de", "el", "en", "fi", "fr", "hr", "nl", "sl", "sl-SI", "sv"};

    private static final String RESOURCES_DIR = "src/main/resources";

    // -----------------------------------------------------------------------
    // CLI option names
    // -----------------------------------------------------------------------

    private static final String BASE_URL_ARG      = "oai-pmh-base-url";
    private static final String VERB_ARG          = "verb";
    private static final String META_PREFIX_ARG   = "metadata-prefix";
    private static final String SETS_ARG          = "sets";
    private static final String FETCH_ALL_ARG     = "fetch-all-sets";
    private static final String FETCH_SET_ARG     = "fetch-set";

    // -----------------------------------------------------------------------
    // Instance state
    // -----------------------------------------------------------------------

    private final HttpClient httpClient;
    private final String oaiPmhBaseUrl;
    private final String verb;
    private final String metadataPrefix;

    private static final Logger logger =
            Logger.getLogger(GetOaiPmhIdentifiers.class.getName());

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Creates a client with the supplied OAI-PMH parameters.
     *
     * @param oaiPmhBaseUrl  base URL of the OAI-PMH endpoint
     * @param verb           OAI-PMH verb (e.g. {@code "ListIdentifiers"})
     * @param metadataPrefix metadata prefix to embed in output GetRecord URLs
     */
    public GetOaiPmhIdentifiers(String oaiPmhBaseUrl, String verb, String metadataPrefix) {
        this.oaiPmhBaseUrl  = oaiPmhBaseUrl;
        this.verb           = verb;
        this.metadataPrefix = metadataPrefix;
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

        String baseUrl = cmd.getOptionValue(BASE_URL_ARG, DEFAULT_OAI_PMH_BASE_URL);
        String verb    = cmd.getOptionValue(VERB_ARG, DEFAULT_VERB);
        String prefix  = cmd.getOptionValue(META_PREFIX_ARG, DEFAULT_METADATA_PREFIX);

        GetOaiPmhIdentifiers client = new GetOaiPmhIdentifiers(baseUrl, verb, prefix);

        try {
            if (cmd.hasOption(FETCH_SET_ARG)) {
                String lang = cmd.getOptionValue(FETCH_SET_ARG);
                if (lang == null || lang.isBlank()) {
                    logSevere("A set must be specified with -s / --fetch-set");
                    return;
                }
                client.fetchIdentifiersForLanguage(lang);
            } else {
                // Default: fetch all sets (also triggered by -F / --fetch-all-sets)
                String[] sets = DEFAULT_SETS;
                if (cmd.hasOption(SETS_ARG)) {
                    sets = cmd.getOptionValue(SETS_ARG).split(",");
                }
                client.fetchAllLanguageIdentifiers(sets);
            }
        } catch (IOException | InterruptedException e) {
            logSevere("Error: %s", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Fetches identifier lists for every set in the supplied array.
     *
     * @param sets array of OAI-PMH set names (language codes)
     * @throws IOException          if an I/O error occurs
     * @throws InterruptedException if interrupted while waiting for HTTP responses
     */
    public void fetchAllLanguageIdentifiers(String[] sets)
            throws IOException, InterruptedException {
        logInfo("Starting OAI-PMH identifier fetch for all sets...");
        for (String set : sets) {
            fetchIdentifiersForLanguage(set);
        }
        logInfo("Finished fetching identifiers for all sets.");
    }

    /**
     * Fetches all identifiers for one language set from the OAI-PMH endpoint,
     * following resumption tokens until the full list has been retrieved, then
     * writes them as full GetRecord URLs to {@code guids_<lang>.txt}.
     *
     * @param set the set name, e.g. {@code "de"}
     * @throws IOException          if an I/O error occurs
     * @throws InterruptedException if interrupted
     */
    public void fetchIdentifiersForLanguage(String set)
            throws IOException, InterruptedException {
        logInfo("Fetching identifiers for set: %s", set);
        List<String> identifiers = new ArrayList<>();

        /** ListIdentifiers with the specified set and metadata prefix.
         * 
         * @param set the set name, e.g. "de"
         * @param verb the OAI-PMH verb to use (e.g. "ListIdentifiers")
         * @param oaiPmhBaseUrl the base URL of the OAI-PMH endpoint
         * 
         * */
        String url = oaiPmhBaseUrl
                + "?verb=" + URLEncoder.encode(verb, StandardCharsets.UTF_8)
                + "&metadataPrefix=" + DEFAULT_METADATA_PREFIX
                + "&set=language:" + URLEncoder.encode(set, StandardCharsets.UTF_8);

        int page = 1;
        while (url != null) {
            logInfo("  Fetching page %d (set=%s): %s", page, set, url);
            String xml = fetchUrl(url);
            List<String> pageIdentifiers = parseIdentifiers(xml);
            identifiers.addAll(pageIdentifiers);
            logInfo("  Page %d: retrieved %d identifier(s) (total so far: %d)",
                    page, pageIdentifiers.size(), identifiers.size());

            String resumptionToken = parseResumptionToken(xml);
            if (resumptionToken != null && !resumptionToken.isBlank()) {
                url = oaiPmhBaseUrl
                        + "?verb=" + URLEncoder.encode(verb, StandardCharsets.UTF_8)
                        + "&resumptionToken="
                        + URLEncoder.encode(resumptionToken, StandardCharsets.UTF_8);
                page++;
            } else {
                url = null;
            }
        }

        logInfo("Fetched %d identifier(s) for set: %s", identifiers.size(), set);
        writeGuidsFile(set, identifiers);
    }

    // -----------------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------------

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
                .header("Accept", "application/xml, text/xml, */*")
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

    // -----------------------------------------------------------------------
    // XML parsing
    // -----------------------------------------------------------------------

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
     * Extracts the resumption token from OAI-PMH XML, or {@code null} if absent.
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
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new java.io.StringReader(xml)));
    }

    // -----------------------------------------------------------------------
    // File writing
    // -----------------------------------------------------------------------

    /**
     * Writes a list of identifiers to {@code guids_<lang>.txt} as full
     * GetRecord URLs.
     *
     * <p>The file is placed in {@value #RESOURCES_DIR} when that directory
     * exists (i.e. when running from source), otherwise in the current working
     * directory (e.g. when running from a JAR).
     *
     * @param set        set name, e.g. "de", used in the output filename and log messages
     * @param identifiers raw identifier strings returned by OAI-PMH
     * @throws IOException if the file cannot be written
     */
    private void writeGuidsFile(String set, List<String> identifiers) throws IOException {
        String filename = "guids_" + set + ".txt";

        Path resourcesDir = Paths.get(RESOURCES_DIR);
        Path outputPath = Files.isDirectory(resourcesDir)
                ? resourcesDir.resolve(filename)
                : Paths.get(filename);

        List<String> lines = new ArrayList<>();
        lines.add("# Identifiers for set: " + set);
        lines.add("# Fetched: " + java.time.Instant.now());
        lines.add("# Count: " + identifiers.size());

        for (String identifier : identifiers) {
            lines.add(buildGetRecordUrl(identifier));
        }

        Files.write(outputPath, lines, StandardCharsets.UTF_8);
        logInfo("✓ Written %d GetRecord URL(s) to %s",
                identifiers.size(), outputPath.toAbsolutePath());
    }

    /**
     * Constructs a full OAI-PMH GetRecord URL for the given raw identifier.
     *
     * @param identifier the plain identifier string (e.g. a hash)
     * @return the full GetRecord URL
     */
    String buildGetRecordUrl(String identifier) {
        return oaiPmhBaseUrl
                + "?verb=GetRecord"
                + "&metadataPrefix=" + URLEncoder.encode(metadataPrefix, StandardCharsets.UTF_8)
                + "&identifier=" + URLEncoder.encode(identifier, StandardCharsets.UTF_8);
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
        options.addOption("b", BASE_URL_ARG, true,
                "OAI-PMH base URL (default: " + DEFAULT_OAI_PMH_BASE_URL + ")");
        options.addOption("v", VERB_ARG, true,
                "OAI-PMH verb for listing identifiers (default: " + DEFAULT_VERB + ")");
        options.addOption("m", META_PREFIX_ARG, true,
                "Metadata prefix for output GetRecord URLs (default: " + DEFAULT_METADATA_PREFIX + ")");
        options.addOption("S", SETS_ARG, true,
                "Comma-separated list of sets to fetch (default: de,el,en,fi,fr,hr,nl,sl,sl-SI,sv)");
        options.addOption("F", FETCH_ALL_ARG, false,
                "Fetch identifiers for all sets (default behaviour when no mode flag is given)");
        options.addOption("s", FETCH_SET_ARG, true,
                "Fetch identifiers for a single set only");
        options.addOption("h", "help", false, "Show help");

        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine cmd = parser.parse(options, args);
            if (cmd.hasOption("h")) {
                new HelpFormatter().printHelp(
                        "java -cp <jar> cessda.cmv.benchmark.GetOaiPmhIdentifiers", options, true);
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