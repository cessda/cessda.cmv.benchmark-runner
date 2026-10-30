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
import org.oclc.oai.harvester2.verb.*;
import org.oclc.oai.harvester2.verb.Set;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
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
 *   -t, --tenant <tenant>              Tenant name; output is written to
 *                                       guids/&lt;tenant&gt;/ (default: cessda)
 *   -h, --help                         Show this help message
 * </pre>
 */
public class GetOaiPmhIdentifiers {

    // -----------------------------------------------------------------------
    // Defaults
    // -----------------------------------------------------------------------

    public static final URI DEFAULT_OAI_PMH_BASE_URL = URI.create("https://datacatalogue.cessda.eu/oai-pmh/v0/oai");

    /** Metadata prefix used when constructing the output GetRecord URLs. */
    public static final String DEFAULT_METADATA_PREFIX = "oai_ddi25";

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
    private final URI oaiPmhBaseUrl;
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
     * @param metadataPrefix metadata prefix to embed in output GetRecord URLs
     * @param outputDir      directory in which to write the guids_*.txt files
     */
    public GetOaiPmhIdentifiers(URI oaiPmhBaseUrl, String metadataPrefix, Path outputDir) {
        this.oaiPmhBaseUrl  = oaiPmhBaseUrl;
        this.metadataPrefix = metadataPrefix;
        this.outputDir      = outputDir;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        logger.log(Level.INFO, "Initialised GetOaiPmhIdentifiers with base URL: {0}, " +
                "metadata prefix: {1}, output directory: {2}", new Object[]{oaiPmhBaseUrl, metadataPrefix, outputDir});
    }

    // -----------------------------------------------------------------------
    // main
    // -----------------------------------------------------------------------

    /**
     * Entry point.
     *
     * @param args command-line arguments
     */
    @SuppressWarnings("java:S106")
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
        String prefix = cmd.getOptionValue(META_PREFIX_ARG, DEFAULT_METADATA_PREFIX);
        String tenant  = cmd.getOptionValue(TENANT_ARG, DEFAULT_TENANT);

        // guids/<tenant>/ — mirrors how BenchmarkService resolves each
        // tenant's data directory (benchmark.data-dir, default ./guids,
        // resolved per tenant) for the web/REST path, so a standalone
        // CLI run and a tenant-scoped API run land in the same place.
        Path outputDir = Paths.get(GUIDS_DIR, tenant);

        URI baseUrl;
        if (baseUrlString != null) {
            baseUrl = new URI(baseUrlString);
        } else {
            baseUrl = DEFAULT_OAI_PMH_BASE_URL;
        }

        GetOaiPmhIdentifiers client = new GetOaiPmhIdentifiers(baseUrl, prefix, outputDir);

        try {
            Files.createDirectories(outputDir);
            if (cmd.hasOption(FETCH_SET_ARG)) {
                String set = cmd.getOptionValue(FETCH_SET_ARG);
                if (set == null || set.isBlank()) {
                    logger.log(Level.SEVERE, "A set must be specified with -s / --fetch-set");
                    return;
                }
                client.fetchIdentifiersForSet(set);
            } else {
                // Default: fetch all sets (also triggered by -F / --fetch-all-sets)
                List<String> sets = Collections.emptyList();
                if (cmd.hasOption(SETS_ARG)) {
                    sets = Arrays.asList(cmd.getOptionValue(SETS_ARG).split(","));
                }
                client.fetchAllSetIdentifiers(sets);
            }
        } catch (OAIException | IOException e) {
            logger.log(Level.SEVERE, "Error: {0}", new Object[]{e.getMessage()});
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
    public void fetchAllSetIdentifiers(List<String> sets)
            throws IOException, InterruptedException, OAIException {
        logger.log(Level.INFO, "Starting OAI-PMH identifier fetch for all sets...");
        if (sets.isEmpty()) {
            fetchIdentifiers();
        } else {
            for (String set : sets) {
                fetchIdentifiersForSet(set);
            }
        }
        logger.log(Level.INFO, "Finished fetching identifiers for all sets.");
    }

    // -----------------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------------

    public void fetchIdentifiers() throws IOException, InterruptedException, OAIException {
        logger.log(Level.INFO, "Fetching identifiers");

        // Filecode is all
        // TODO: could conflict with a set named all, fix this
        String fileCode = "all";

        var identifiers = getIdentifiers(null);

        logger.log(Level.INFO, "Fetched {0} identifier(s)", new Object[]{identifiers.size()});
        writeGuidsFile(fileCode, identifiers);
    }

    /**
     * Fetches all identifiers for one set from the OAI-PMH endpoint,
     * following resumption tokens until the full list has been retrieved, then
     * writes them as full GetRecord URLs to {@code guids_<set>.txt}.
     *
     * @param setSpec the set name, e.g. {@code openaire_data}
     * @throws IOException          if an I/O error occurs
     * @throws InterruptedException if interrupted
     */
    public void fetchIdentifiersForSet(String setSpec)
            throws IOException, InterruptedException, OAIException {
        logger.log(Level.INFO, "Fetching identifiers for set: {0}", new Object[]{setSpec});

        // The output filename must stay filesystem-safe (":" is
        // invalid in Windows paths) and match the guids_<code>.txt
        // convention the rest of the pipeline expects -- derived from
        // the tail of the setSpec, not the setSpec itself.
        String fileCode = sanitizeSetSpecForFilename(setSpec);

        // Also use the configured metadataPrefix (not the compiled-in
        // default) so -m/--metadata-prefix is honoured on this request
        // too, matching buildGetRecordUrl.
        var identifiers = getIdentifiers(setSpec);

        logger.log(Level.INFO, "Fetched {0} identifier(s) for set: {1}", new Object[]{identifiers.size(), setSpec});
        writeGuidsFile(fileCode, identifiers);
    }

    private List<String> getIdentifiers(String setSpec) throws IOException, OAIException, InterruptedException {
        var identifiers = new ArrayList<String>();

        ListIdentifiers response = ListIdentifiers.instance(
                httpClient,
                oaiPmhBaseUrl,
                metadataPrefix,
                setSpec,
                null,
                null
        );

        Optional<String> resumptionToken;
        do {
            List<RecordHeader> pageIdentifiers = response.getIdentifiers();

            for (var i : pageIdentifiers) {
                identifiers.add(i.identifier());
            }

            logger.log(Level.FINE, "Retrieved {0} identifier(s) (total so far: {1})", new Object[]{pageIdentifiers.size(), identifiers.size()});

            resumptionToken = response.getResumptionToken();
            if (resumptionToken.isPresent()) {
                response = ListIdentifiers.instance(httpClient, oaiPmhBaseUrl, resumptionToken.get());
            }
        } while (resumptionToken.isPresent());

        return identifiers;
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
        CommandLine cmd = parser.parse(options, args);
        if (cmd.hasOption("h")) {
            new HelpFormatter().printHelp(
                    "java -cp <jar> cessda.cmv.benchmark.GetOaiPmhIdentifiers", options, true);
            System.exit(0);
        }
        return cmd;
    }

    // -----------------------------------------------------------------------
    // XML parsing
    // -----------------------------------------------------------------------

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
     * @throws OAIException         if the response is malformed
     * @throws IOException          if the request fails, the response
     *                               cannot be parsed, or the response
     *                               contains an OAI-PMH {@code <error>}
     *                               other than {@code noSetHierarchy}
     * @throws InterruptedException if interrupted while waiting for the
     *                               HTTP response
     */
    public List<SetInfo> listSets() throws IOException, InterruptedException, OAIException {
        List<SetInfo> sets = new ArrayList<>();
        ListSets response = ListSets.instance(httpClient, oaiPmhBaseUrl);
        int page = 1;
        Optional<String> resumptionToken;
        do {
            logger.log(Level.INFO, "  Fetching ListSets page {0}", new Object[]{page});
            List<Set> setNodes = response.getSets();
            for (Set setNode : setNodes) {
                String setSpec = setNode.spec();
                String setName = setNode.name();
                sets.add(new SetInfo(setSpec, setName != null ? setName : setSpec));
            }

            resumptionToken = response.getResumptionToken();
            if (resumptionToken.isPresent()) {
                response = ListSets.instance(httpClient, oaiPmhBaseUrl, resumptionToken.get());
                page++;
            }
        } while (resumptionToken.isPresent());
        logger.log(Level.INFO, "ListSets returned {0} set(s)", sets.size());
        return sets;
    }

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

        try (var writer = new PrintWriter(Files.newBufferedWriter(outputPath))) {
            writer.println("# Identifiers for set: " + fileCode);
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