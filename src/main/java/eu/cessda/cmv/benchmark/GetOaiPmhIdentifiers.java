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

package eu.cessda.cmv.benchmark;

import org.apache.commons.cli.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
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
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    public static final URI DEFAULT_OAI_PMH_BASE_URL = URI.create("https://datacatalogue.cessda.eu/oai-pmh/v0/oai");

    public static final String DEFAULT_VERB = "ListIdentifiers";

    /** Metadata prefix used when constructing the output GetRecord URLs. */
    public static final String DEFAULT_METADATA_PREFIX = "oai_ddi25";

    public static final List<String> DEFAULT_SETS =
            List.of("de", "el", "en", "fi", "fr", "hr", "nl", "sl", "sl-SI", "sv");

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
    private final URI oaiPmhBaseUrl;
    private final String verb;
    private final String metadataPrefix;

    private final Path outputDir;

    private static final Logger logger =
            Logger.getLogger(GetOaiPmhIdentifiers.class.getName());

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Creates a client with the supplied OAI-PMH parameters and an explicit
     * output directory.  All {@code guids_<set>.txt} files will be written
     * under {@code outputDir}, which must already exist or be created by the
     * caller before invoking any fetch method.
     *
     * @param oaiPmhBaseUrl  base URL of the OAI-PMH endpoint
     * @param verb           OAI-PMH verb (e.g. {@code "ListIdentifiers"})
     * @param metadataPrefix metadata prefix to embed in output GetRecord URLs
     * @param outputDir      directory in which to write the guids_*.txt files
     */
    public GetOaiPmhIdentifiers(URI oaiPmhBaseUrl, String verb,
                                String metadataPrefix, Path outputDir) {
        this.oaiPmhBaseUrl  = oaiPmhBaseUrl;
        this.verb           = verb;
        this.metadataPrefix = metadataPrefix;
        this.outputDir      = outputDir;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        logger.log(Level.INFO, "Initialised GetOaiPmhIdentifiers with base URL: {0}, verb: {1}, " +
                "metadata prefix: {2}, output directory: {3}", new Object[]{oaiPmhBaseUrl, verb, metadataPrefix, outputDir});
    }

    // -----------------------------------------------------------------------
    // main
    // -----------------------------------------------------------------------

    /**
     * Entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) throws URISyntaxException, InterruptedException {
        logger.setLevel(Level.INFO);

        CommandLine cmd;
        try {
            cmd = parseArgs(args);
        } catch (ParseException e) {
            System.err.println("Error parsing arguments: " + e.getMessage());
            System.err.println("Use -h or --help for usage information.");
            System.exit(-1);
            return;
        }

        String baseUrlString = cmd.getOptionValue(BASE_URL_ARG);
        String verb = cmd.getOptionValue(VERB_ARG, DEFAULT_VERB);
        String prefix = cmd.getOptionValue(META_PREFIX_ARG, DEFAULT_METADATA_PREFIX);

        URI baseUrl;
        if (baseUrlString != null) {
            baseUrl = new URI(baseUrlString);
        } else {
            baseUrl = DEFAULT_OAI_PMH_BASE_URL;
        }

        GetOaiPmhIdentifiers client = new GetOaiPmhIdentifiers(baseUrl, verb, prefix, Paths.get("output"));

        try {
            if (cmd.hasOption(FETCH_SET_ARG)) {
                String set = cmd.getOptionValue(FETCH_SET_ARG);
                if (set == null || set.isBlank()) {
                    logger.log(Level.SEVERE, "A set must be specified with -s / --fetch-set");
                    return;
                }
                client.fetchIdentifiersForSet(set);
            } else {
                // Default: fetch all sets (also triggered by -F / --fetch-all-sets)
                List<String> sets = DEFAULT_SETS;
                if (cmd.hasOption(SETS_ARG)) {
                    sets = Arrays.asList(cmd.getOptionValue(SETS_ARG).split(","));
                }
                client.fetchAllSetIdentifiers(sets);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error: {0}", new Object[]{e.getMessage()});
        }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Builds and parses the command-line options.
     *
     * @param args raw command-line arguments
     * @return parsed {@link CommandLine}
     * @throws ParseException if argument parsing fails
     */
    static CommandLine parseArgs(String... args) throws ParseException {
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

        DefaultParser parser = new DefaultParser(false);

        CommandLine cmd = parser.parse(options, args);
        if (cmd.hasOption("h")) {
            new HelpFormatter().printHelp(
                    "java -cp <jar> cessda.cmv.benchmark.GetOaiPmhIdentifiers", options, true);
            System.exit(0);
        }
        return cmd;
    }

    /**
     * Fetches identifier lists for every set in the supplied array.
     *
     * @param sets array of OAI-PMH set names (set codes)
     * @throws IOException          if an I/O error occurs
     * @throws InterruptedException if interrupted while waiting for HTTP responses
     */
    public void fetchAllSetIdentifiers(List<String> sets)
            throws IOException, InterruptedException {
        logger.log(Level.INFO, "Starting OAI-PMH identifier fetch for all sets...");
        for (String set : sets) {
            fetchIdentifiersForSet(set);
        }
        logger.log(Level.INFO, "Finished fetching identifiers for all sets.");
    }

    // -----------------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------------

    /**
     * Fetches all identifiers for one set from the OAI-PMH endpoint,
     * following resumption tokens until the full list has been retrieved, then
     * writes them as full GetRecord URLs to {@code guids_<set>.txt}.
     *
     * @param set the set name, e.g. {@code "de"}
     * @throws IOException          if an I/O error occurs
     * @throws InterruptedException if interrupted
     */
    public void fetchIdentifiersForSet(String set)
            throws IOException, InterruptedException {
        logger.log(Level.INFO, "Fetching identifiers for set: {0}", new Object[]{set});
        List<String> identifiers = new ArrayList<>();

        // ListIdentifiers with the specified set and metadata prefix.
        URI url = URI.create(oaiPmhBaseUrl
                + "?verb=" + URLEncoder.encode(verb, StandardCharsets.UTF_8)
                + "&metadataPrefix=" + DEFAULT_METADATA_PREFIX
                + "&set=set:" + URLEncoder.encode(set, StandardCharsets.UTF_8));

        int page = 1;
        while (true) {
            logger.log(Level.INFO, "Fetching page {0} (set={1}): {2}", new Object[]{page, set, url});
            Document xml;
            try (InputStream xmlStream = fetchUrl(url, HttpResponse.BodyHandlers.ofInputStream())) {
                InputSource inputSource = new InputSource();
                inputSource.setSystemId(url.toString());
                inputSource.setByteStream(xmlStream);
                xml = parseXml(inputSource);
            } catch (ParserConfigurationException | SAXException e) {
                throw new IOException("Failed to parse OAI-PMH XML: " + e.getMessage(), e);
            }
            List<String> pageIdentifiers = parseIdentifiers(xml);
            identifiers.addAll(pageIdentifiers);
            logger.log(Level.INFO, "Page {0}: retrieved {1} identifier(s) (total so far: {2})", new Object[]{page, pageIdentifiers.size(), identifiers.size()});

            String resumptionToken = parseResumptionToken(xml);
            if (resumptionToken != null && !resumptionToken.isBlank()) {
                url = URI.create(oaiPmhBaseUrl
                        + "?verb=" + URLEncoder.encode(verb, StandardCharsets.UTF_8)
                        + "&resumptionToken="
                        + URLEncoder.encode(resumptionToken, StandardCharsets.UTF_8));
                page++;
            } else {
                break;
            }
        }

        logger.log(Level.INFO, "Fetched {0} identifier(s) for set: {1}", new Object[]{identifiers.size(), set});
        writeGuidsFile(set, identifiers);
    }

    // -----------------------------------------------------------------------
    // XML parsing
    // -----------------------------------------------------------------------

    /**
     * Performs a simple HTTP GET and returns the response body as a String.
     *
     * @param url the URL to fetch
     * @return response body
     * @throws IOException          if the request fails or returns a non-2xx status
     * @throws InterruptedException if interrupted
     */
    private <T> T fetchUrl(URI url, HttpResponse.BodyHandler<T> bodyHandler) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(url)
                .header("Accept", "application/xml, text/xml, */*")
                .GET()
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<T> response = httpClient.send(request, bodyHandler);

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " fetching " + url);
        }

        return response.body();
    }

    /**
     * Parses {@code <identifier>} values from OAI-PMH ListIdentifiers XML.
     *
     * @param doc the XML document
     * @return list of identifier strings
     */
    private List<String> parseIdentifiers(Document doc) {
        List<String> ids = new ArrayList<>();
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
        return ids;
    }

    /**
     * Extracts the resumption token from OAI-PMH XML, or {@code null} if absent.
     *
     * @param doc the XML document
     * @return resumption token string, or {@code null}
     */
    private String parseResumptionToken(Document doc) {
        NodeList nodes = doc.getElementsByTagNameNS("*", "resumptionToken");
        if (nodes.getLength() == 0) {
            nodes = doc.getElementsByTagName("resumptionToken");
        }
        if (nodes.getLength() > 0) {
            Element el = (Element) nodes.item(0);
            String token = el.getTextContent().trim();
            return token.isBlank() ? null : token;
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // File writing
    // -----------------------------------------------------------------------

    /**
     * Parses an XML string into a {@link Document}.
     */
    private Document parseXml(InputSource inputSource)
            throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(inputSource);
    }

    /**
     * Writes a list of identifiers to {@code guids_<set>.txt} as full
     * GetRecord URLs, under the {@code outputDir} supplied at construction time.
     *
     * @param set         set name, e.g. "de", used in the output filename
     * @param identifiers raw identifier strings returned by OAI-PMH
     * @throws IOException if the file cannot be written
     */
    private void writeGuidsFile(String set, List<String> identifiers) throws IOException {
        Path outputPath = outputDir.resolve("guids_" + set + ".txt");

        try (var writer = new PrintWriter(Files.newBufferedWriter(outputPath))) {
            writer.println("# Identifiers for set: " + set);
            writer.println("# Fetched: " + java.time.Instant.now());
            writer.println("# Count: " + identifiers.size());

            for (String identifier : identifiers) {
                writer.println(buildGetRecordUrl(identifier));
            }
        }

        logger.log(Level.INFO, "Written {0} GetRecord URL(s) to {1}", new Object[]{identifiers.size(), outputPath.toAbsolutePath()});
    }

    // -----------------------------------------------------------------------
    // CLI
    // -----------------------------------------------------------------------

    /**
     * Constructs a full OAI-PMH GetRecord URL for the given raw identifier.
     *
     * @param identifier the plain identifier string (e.g. a hash)
     * @return the full GetRecord URL
     */
    URI buildGetRecordUrl(String identifier) {
        String uriString = oaiPmhBaseUrl
                + "?verb=GetRecord"
                + "&metadataPrefix=" + URLEncoder.encode(metadataPrefix, StandardCharsets.UTF_8)
                + "&identifier=" + URLEncoder.encode(identifier, StandardCharsets.UTF_8);
        return URI.create(uriString);
    }
}