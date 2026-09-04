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
 *   -t, --tenant <tenant>              Tenant name; output is written to
 *                                       guids/&lt;tenant&gt;/ (default: cessda)
 *   -h, --help                         Show this help message
 * </pre>
 */
public class GetOaiPmhIdentifiers {

    // -----------------------------------------------------------------------
    // Defaults
    // -----------------------------------------------------------------------

    public static final String DEFAULT_OAI_PMH_BASE_URL =
            "https://datacatalogue.cessda.eu/oai-pmh/v0/oai";

    public static final String DEFAULT_VERB = "ListIdentifiers";

    /** Metadata prefix used when constructing the output GetRecord URLs. */
    public static final String DEFAULT_METADATA_PREFIX = "oai_ddi25";

    public static final String[] DEFAULT_SETS =
            {"de", "el", "en", "fi", "fr", "hr", "nl", "sl", "sl-SI", "sv"};

    /**
     * Prefix applied to each entry of {@link #DEFAULT_SETS} (or a set
     * name passed via {@code -s}/{@code --fetch-set} or
     * {@code -S}/{@code --sets}) to build the OAI-PMH {@code setSpec}
     * sent as the {@code set} query parameter. This repository's sets
     * are keyed by language, e.g. {@code language:hr} for the "hr" set
     * (confirmed against {@code verb=ListSets} on the live endpoint) --
     * the bare code alone is not a valid setSpec and is rejected with
     * an OAI-PMH {@code noRecordsMatch} error.
     */
    static final String SET_SPEC_PREFIX = "language:";

    /**
     * Tenant name used for the standalone CLI's output directory
     * ({@code guids/<tenant>/}) when {@code -t}/{@code --tenant} is
     * not supplied. Matches the {@code "cessda"} tenant id configured
     * under {@code tenants.config} in {@code application.yaml}.
     */
    public static final String DEFAULT_TENANT = "cessda";

    /** Root directory under which each tenant's guids_<set>.txt files are written. */
    private static final String GUIDS_DIR = "guids";

    // -----------------------------------------------------------------------
    // CLI option names
    // -----------------------------------------------------------------------

    private static final String BASE_URL_ARG      = "oai-pmh-base-url";
    private static final String VERB_ARG          = "verb";
    private static final String META_PREFIX_ARG   = "metadata-prefix";
    private static final String SETS_ARG          = "sets";
    private static final String FETCH_ALL_ARG     = "fetch-all-sets";
    private static final String FETCH_SET_ARG     = "fetch-set";
    private static final String TENANT_ARG        = "tenant";

    // -----------------------------------------------------------------------
    // Instance state
    // -----------------------------------------------------------------------

    private final HttpClient httpClient;
    private final String oaiPmhBaseUrl;
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
    public GetOaiPmhIdentifiers(String oaiPmhBaseUrl, String verb,
                                String metadataPrefix, Path outputDir) {
        this.oaiPmhBaseUrl  = oaiPmhBaseUrl;
        this.verb           = verb;
        this.metadataPrefix = metadataPrefix;
        this.outputDir      = outputDir;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        logInfo("Initialised GetOaiPmhIdentifiers with base URL: %s, verb: %s, " +
                "metadata prefix: %s, output directory: %s",
                oaiPmhBaseUrl, verb, metadataPrefix, outputDir != null ? outputDir.toAbsolutePath() : "null");
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
        String tenant  = cmd.getOptionValue(TENANT_ARG, DEFAULT_TENANT);

        // guids/<tenant>/ — mirrors how BenchmarkService resolves each
        // tenant's data directory (benchmark.data-dir, default ./guids,
        // resolved per tenant) for the web/REST path, so a standalone
        // CLI run and a tenant-scoped API run land in the same place.
        Path outputDir = Paths.get(GUIDS_DIR, tenant);

        GetOaiPmhIdentifiers client = new GetOaiPmhIdentifiers(baseUrl, verb, prefix, outputDir);

        try {
            Files.createDirectories(outputDir);
            if (cmd.hasOption(FETCH_SET_ARG)) {
                String set = cmd.getOptionValue(FETCH_SET_ARG);
                if (set == null || set.isBlank()) {
                    logSevere("A set must be specified with -s / --fetch-set");
                    return;
                }
                client.fetchIdentifiersForSet(set);
            } else {
                // Default: fetch all sets (also triggered by -F / --fetch-all-sets)
                String[] sets = DEFAULT_SETS;
                if (cmd.hasOption(SETS_ARG)) {
                    sets = cmd.getOptionValue(SETS_ARG).split(",");
                }
                client.fetchAllSetIdentifiers(sets);
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
     * @param sets array of OAI-PMH set names (set codes)
     * @throws IOException          if an I/O error occurs
     * @throws InterruptedException if interrupted while waiting for HTTP responses
     */
    public void fetchAllSetIdentifiers(String[] sets)
            throws IOException, InterruptedException {
        logInfo("Starting OAI-PMH identifier fetch for all sets...");
        for (String set : sets) {
            fetchIdentifiersForSet(set);
        }
        logInfo("Finished fetching identifiers for all sets.");
    }

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
        logInfo("Fetching identifiers for set: %s", set);
        List<String> identifiers = new ArrayList<>();

        /** ListIdentifiers with the specified set and metadata prefix.
         *
         * @param set the set name, e.g. "de"
         * @param verb the OAI-PMH verb to use (e.g. "ListIdentifiers")
         * @param oaiPmhBaseUrl the base URL of the OAI-PMH endpoint
         *
         * */
        String setSpec = qualifySetSpec(set);

        // The output filename must stay filesystem-safe (":" is
        // invalid in Windows paths) and match the guids_<code>.txt
        // convention the rest of the pipeline expects -- derived from
        // the tail of the setSpec, not the setSpec itself.
        String fileCode = sanitizeSetSpecForFilename(setSpec);

        // Also use the configured metadataPrefix (not the compiled-in
        // default) so -m/--metadata-prefix is honoured on this request
        // too, matching buildGetRecordUrl.
        String url = oaiPmhBaseUrl
                + "?verb=" + URLEncoder.encode(verb, StandardCharsets.UTF_8)
                + "&metadataPrefix=" + URLEncoder.encode(metadataPrefix, StandardCharsets.UTF_8)
                + "&set=" + URLEncoder.encode(setSpec, StandardCharsets.UTF_8);

        int page = 1;
        while (url != null) {
            logInfo("  Fetching page %d (set=%s): %s", page, setSpec, url);
            String xml = fetchUrl(url);
            checkForOaiPmhError(xml, "set '" + setSpec + "', page " + page);
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

        logInfo("Fetched %d identifier(s) for set: %s", identifiers.size(), setSpec);
        writeGuidsFile(fileCode, identifiers);
    }

    /**
     * Derives a filesystem-safe filename fragment from a (possibly
     * fully qualified) OAI-PMH setSpec.
     *
     * <p>Takes the substring after the last {@code ':'} when the
     * setSpec is qualified (e.g. {@code "language:hr"} -&gt;
     * {@code "hr"}; a nested spec like {@code "a:b:c"} -&gt;
     * {@code "c"}), or the whole value when it isn't. Any character
     * that isn't alphanumeric, a dot, an underscore, or a hyphen is
     * then replaced with an underscore, matching the sanitisation
     * {@code RunBenchmarkAssessment} applies to GUIDs when naming its
     * own output files.</p>
     *
     * @param setSpec the setSpec to derive a filename fragment from
     * @return a filesystem-safe fragment, never containing {@code ':'}
     */
    /**
     * Qualifies a set value into a full OAI-PMH setSpec.
     *
     * <p>{@code set} may be a short code (e.g. {@code "de"}) -- this
     * repository expects those as {@code "language:<code>"}, see
     * {@link #SET_SPEC_PREFIX} -- or it may already be a fully
     * qualified setSpec as returned by {@link #listSets()} (e.g.
     * {@code "language:hr"}, or a different tenant's own scheme
     * entirely, which need not use a {@code "language:"} prefix or
     * even contain a colon at all). The prefix is only prepended when
     * the value doesn't already look qualified, so short codes keep
     * working unchanged for the CLI's compiled-in CESSDA default while
     * a setSpec obtained live from whichever endpoint is actually
     * configured for a tenant is never double-prefixed.</p>
     *
     * @param set a short code or an already-qualified setSpec
     * @return a setSpec ready to send as the {@code set} query
     *         parameter
     */
    static String qualifySetSpec(String set) {
        return set.contains(":") ? set : SET_SPEC_PREFIX + set;
    }

    static String sanitizeSetSpecForFilename(String setSpec) {
        String tail = setSpec.contains(":")
                ? setSpec.substring(setSpec.lastIndexOf(':') + 1)
                : setSpec;
        return tail.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * One entry from an OAI-PMH {@code ListSets} response.
     *
     * @param setSpec the raw {@code <setSpec>} value (e.g.
     *                {@code "language:hr"}), suitable for passing
     *                straight back to {@link #fetchIdentifiersForSet}
     * @param setName the human-readable {@code <setName>} value (e.g.
     *                {@code "Language hr"}), suitable for display
     */
    public record SetInfo(String setSpec, String setName) {}

    /**
     * Calls {@code verb=ListSets} on the configured OAI-PMH endpoint
     * and returns every set it reports.
     *
     * <p>This is the authoritative source of which sets an endpoint
     * actually offers -- there is no compiled-in or configured
     * fallback list, since a hand-maintained copy inevitably drifts
     * out of sync with the live repository (CESSDA's own catalogue no
     * longer offers every set once listed in this project's static
     * defaults). Every OAI-PMH repository is required to support
     * {@code ListSets} if it supports selective harvesting at all, so
     * this works for any tenant's endpoint, whatever its own setSpec
     * naming scheme happens to be.</p>
     *
     * @return sets in the order the repository returned them; empty if
     *         the repository has no sets configured (a valid
     *         {@code noSetHierarchy} response, not an error)
     * @throws IOException          if the request fails, the response
     *                               cannot be parsed, or the response
     *                               contains an OAI-PMH {@code <error>}
     *                               other than {@code noSetHierarchy}
     * @throws InterruptedException if interrupted while waiting for the
     *                               HTTP response
     */
    public List<SetInfo> listSets() throws IOException, InterruptedException {
        List<SetInfo> sets = new ArrayList<>();
        String url = oaiPmhBaseUrl + "?verb=ListSets";
        int page = 1;
        while (url != null) {
            logInfo("  Fetching ListSets page %d: %s", page, url);
            String xml = fetchUrl(url);
            try {
                Document doc = parseXml(xml);
                NodeList errorNodes = doc.getElementsByTagNameNS("*", "error");
                if (errorNodes.getLength() == 0) {
                    errorNodes = doc.getElementsByTagName("error");
                }
                if (errorNodes.getLength() > 0) {
                    String code = ((Element) errorNodes.item(0)).getAttribute("code");
                    // noSetHierarchy just means the repository doesn't
                    // partition its records into sets at all -- a
                    // legitimate "there are no sets" answer, not a
                    // failure.
                    if ("noSetHierarchy".equals(code)) {
                        return List.of();
                    }
                    checkForOaiPmhError(xml, "ListSets, page " + page);
                }

                NodeList setNodes = doc.getElementsByTagNameNS("*", "set");
                if (setNodes.getLength() == 0) {
                    setNodes = doc.getElementsByTagName("set");
                }
                for (int i = 0; i < setNodes.getLength(); i++) {
                    Element setEl = (Element) setNodes.item(i);
                    String setSpec = childText(setEl, "setSpec");
                    String setName = childText(setEl, "setName");
                    if (setSpec != null && !setSpec.isBlank()) {
                        sets.add(new SetInfo(setSpec, setName != null ? setName : setSpec));
                    }
                }

                String resumptionToken = parseResumptionToken(xml);
                if (resumptionToken != null && !resumptionToken.isBlank()) {
                    url = oaiPmhBaseUrl
                            + "?verb=ListSets&resumptionToken="
                            + URLEncoder.encode(resumptionToken, StandardCharsets.UTF_8);
                    page++;
                } else {
                    url = null;
                }
            } catch (ParserConfigurationException | SAXException e) {
                throw new IOException("Failed to parse ListSets response (page "
                        + page + "): " + e.getMessage(), e);
            }
        }
        logInfo("ListSets returned %d set(s)", sets.size());
        return sets;
    }

    /**
     * Returns the text content of the first direct child of
     * {@code parent} with the given local name, or {@code null} if no
     * such child exists.
     */
    private static String childText(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node child = children.item(i);
            if (child instanceof Element childEl && localName.equals(childEl.getLocalName())) {
                return childEl.getTextContent().trim();
            }
        }
        return null;
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
     * Checks a raw OAI-PMH XML response for a top-level {@code <error>}
     * element and throws if one is present.
     *
     * <p>OAI-PMH repositories report protocol-level failures (e.g.
     * {@code badVerb}, {@code badArgument}, {@code noRecordsMatch}) as a
     * normal HTTP 200 response whose body is an {@code <error>} element,
     * not as a non-2xx status. Left unchecked, a failed request is
     * indistinguishable from one that legitimately matched zero
     * records: both parse to an empty identifier list with no
     * resumption token, so the failure was previously silently written
     * out as an empty {@code guids_<set>.txt} file instead of being
     * reported.</p>
     *
     * @param xml     the raw XML response body
     * @param context short description of the request (e.g. the set
     *                name and page number) included in the exception
     *                message if an error is found
     * @throws IOException if the response contains an {@code <error>}
     *                      element, or if the XML cannot be parsed
     */
    private void checkForOaiPmhError(String xml, String context) throws IOException {
        try {
            Document doc = parseXml(xml);
            NodeList nodes = doc.getElementsByTagNameNS("*", "error");
            if (nodes.getLength() == 0) {
                nodes = doc.getElementsByTagName("error");
            }
            if (nodes.getLength() > 0) {
                Element errorEl = (Element) nodes.item(0);
                String code = errorEl.getAttribute("code");
                String message = errorEl.getTextContent().trim();
                throw new IOException("OAI-PMH error"
                        + (code.isBlank() ? "" : " (" + code + ")")
                        + " while fetching " + context + ": "
                        + (message.isBlank() ? "<no message>" : message));
            }
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Failed to parse OAI-PMH XML while checking for errors ("
                    + context + "): " + e.getMessage(), e);
        }
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
     * Writes a list of identifiers to {@code guids_<fileCode>.txt} as
     * full GetRecord URLs, under the {@code outputDir} supplied at
     * construction time.
     *
     * @param fileCode    filesystem-safe code used in the output
     *                    filename, e.g. "de" -- see
     *                    {@link #sanitizeSetSpecForFilename(String)}
     * @param identifiers raw identifier strings returned by OAI-PMH
     * @throws IOException if the file cannot be written
     */
    private void writeGuidsFile(String fileCode, List<String> identifiers) throws IOException {
        Path outputPath = outputDir.resolve("guids_" + fileCode + ".txt");

        List<String> lines = new ArrayList<>();
        lines.add("# Identifiers for set: " + fileCode);
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
        options.addOption("t", TENANT_ARG, true,
                "Tenant name; output is written to guids/<tenant>/ (default: " + DEFAULT_TENANT + ")");
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

    // -----------------------------------------------------------------------
    // Logging helpers
    // -----------------------------------------------------------------------

    static void logInfo(String message, Object... args) {
        if (logger.isLoggable(Level.INFO)) {
            logger.info(args.length == 0 ? message : String.format(message, args));
        }
    }

    static void logSevere(String message, Object... args) {
        if (logger.isLoggable(Level.SEVERE)) {
            logger.severe(args.length == 0 ? message : String.format(message, args));
        }
    }
}