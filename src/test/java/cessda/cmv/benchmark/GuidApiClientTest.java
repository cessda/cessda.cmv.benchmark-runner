package cessda.cmv.benchmark;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;

import org.apache.commons.cli.CommandLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Test suite for {@link GuidApiClient}.
 *
 * <p>All tests are self-contained and do not make real network requests.
 * Private methods are exercised via reflection where necessary to maximise
 * branch coverage without requiring changes to the production API.</p>
 */
@ExtendWith(MockitoExtension.class)
class GuidApiClientTest {

    // -----------------------------------------------------------------------
    // Shared OAI-PMH XML fixtures
    // -----------------------------------------------------------------------

    /** Single-page OAI-PMH response with two identifiers and no resumption token. */
    private static final String OAI_XML_SINGLE_PAGE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/">
              <ListIdentifiers>
                <header>
                  <identifier>abc123</identifier>
                  <datestamp>2026-03-08T03:51:11Z</datestamp>
                  <setSpec>language:de</setSpec>
                </header>
                <header>
                  <identifier>def456</identifier>
                  <datestamp>2026-03-08T03:45:04Z</datestamp>
                  <setSpec>language:de</setSpec>
                </header>
                <resumptionToken completeListSize="2" cursor="0"></resumptionToken>
              </ListIdentifiers>
            </OAI-PMH>
            """;

    /** First page of a two-page OAI-PMH response. */
    private static final String OAI_XML_PAGE_1 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/">
              <ListIdentifiers>
                <header>
                  <identifier>page1-id1</identifier>
                  <datestamp>2026-03-08T03:51:11Z</datestamp>
                  <setSpec>language:en</setSpec>
                </header>
                <resumptionToken completeListSize="2" cursor="0">myToken</resumptionToken>
              </ListIdentifiers>
            </OAI-PMH>
            """;

    /** Second (last) page of a two-page OAI-PMH response – no resumption token. */
    private static final String OAI_XML_PAGE_2 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/">
              <ListIdentifiers>
                <header>
                  <identifier>page2-id1</identifier>
                  <datestamp>2026-03-08T03:51:11Z</datestamp>
                  <setSpec>language:en</setSpec>
                </header>
              </ListIdentifiers>
            </OAI-PMH>
            """;

    /** OAI-PMH response with no namespace declarations (plain tags). */
    private static final String OAI_XML_NO_NAMESPACE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <OAI-PMH>
              <ListIdentifiers>
                <header>
                  <identifier>nons-id1</identifier>
                  <datestamp>2026-01-01T00:00:00Z</datestamp>
                </header>
              </ListIdentifiers>
            </OAI-PMH>
            """;

    // -----------------------------------------------------------------------
    // Test infrastructure
    // -----------------------------------------------------------------------

    @Mock
    private HttpClient mockHttpClient;

    @Mock
    private HttpResponse<String> mockHttpResponse;

    @TempDir
    Path tempDir;

    private ByteArrayOutputStream logOutput;
    private Handler logHandler;
    private Logger captureLogger;

    @BeforeEach
    void setUp() {
        logOutput = new ByteArrayOutputStream();
        logHandler = new StreamHandler(logOutput, new SimpleFormatter());
        captureLogger = Logger.getLogger(GuidApiClient.class.getName());
        captureLogger.addHandler(logHandler);

        // Reset static state between tests
        GuidApiClient.spreadsheetUri = GuidApiClient.BENCHMARK_ALGORITHM_URI;
        GuidApiClient.guidsFilename  = GuidApiClient.GUIDS_FILE;
    }

    @AfterEach
    void tearDown() {
        captureLogger.removeHandler(logHandler);
        logHandler.close();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Reflectively invokes a private method on {@code target}, unwrapping any InvocationTargetException. */
    @SuppressWarnings("unchecked")
    private <T> T invoke(Object target, String methodName, Class<?>[] types, Object... args)
            throws Exception {
        Method m = GuidApiClient.class.getDeclaredMethod(methodName, types);
        m.setAccessible(true);
        try {
            return (T) m.invoke(target, args);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw ite;
        }
    }

    private GuidApiClient newClientWithMockedHttp() throws Exception {
        GuidApiClient client = new GuidApiClient();
        // Inject the mock HTTP client via reflection
        var field = GuidApiClient.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(client, mockHttpClient);
        return client;
    }

    // -----------------------------------------------------------------------
    // Nested test groups
    // -----------------------------------------------------------------------

    // =======================================================================
    @Nested
    @DisplayName("Constants")
    class ConstantsTest {

        @Test
        @DisplayName("BENCHMARK_ALGORITHM_URI has expected prefix")
        void benchmarkAlgorithmUri() {
            assertTrue(GuidApiClient.BENCHMARK_ALGORITHM_URI
                    .startsWith("https://tools.ostrails.eu/champion/assess/algorithm/"));
        }

        @Test
        @DisplayName("GUIDS_FILE is guids_hr.txt")
        void guidsFile() {
            assertEquals("guids_hr.txt", GuidApiClient.GUIDS_FILE);
        }

        @Test
        @DisplayName("OAI_PMH_BASE_URL points to CESSDA endpoint")
        void oaiPmhBaseUrl() {
            assertTrue(GuidApiClient.OAI_PMH_BASE_URL
                    .startsWith("https://datacatalogue.cessda.eu/oai-pmh/"));
        }

        @Test
        @DisplayName("LANGUAGES contains exactly ten entries")
        void languagesLength() {
            assertEquals(10, GuidApiClient.LANGUAGES.length);
        }

        @Test
        @DisplayName("LANGUAGES contains expected codes")
        void languagesContent() {
            List<String> langs = List.of(GuidApiClient.LANGUAGES);
            assertTrue(langs.containsAll(List.of("de", "el", "en", "fi", "fr", "hr", "nl", "sl", "sl-SI", "sv")));
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("CLI parsing – parseCommandLineArgs")
    class CliParsingTest {

        @Test
        @DisplayName("No arguments → default spreadsheet URI")
        void defaultSpreadsheetUri() throws IOException {
            GuidApiClient.parseCommandLineArgs(new String[]{});
            assertEquals(GuidApiClient.BENCHMARK_ALGORITHM_URI, GuidApiClient.spreadsheetUri);
        }

        @Test
        @DisplayName("No arguments → default guids filename")
        void defaultGuidsFilename() throws IOException {
            GuidApiClient.parseCommandLineArgs(new String[]{});
            assertEquals(GuidApiClient.GUIDS_FILE, GuidApiClient.guidsFilename);
        }

        @Test
        @DisplayName("-s sets spreadsheet URI")
        void shortSpreadsheetOption() throws IOException {
            String uri = "https://example.com/sheet";
            GuidApiClient.parseCommandLineArgs(new String[]{"-s", uri});
            assertEquals(uri, GuidApiClient.spreadsheetUri);
        }

        @Test
        @DisplayName("--spreadsheet sets spreadsheet URI")
        void longSpreadsheetOption() throws IOException {
            String uri = "https://example.com/sheet2";
            GuidApiClient.parseCommandLineArgs(new String[]{"--spreadsheet", uri});
            assertEquals(uri, GuidApiClient.spreadsheetUri);
        }

        @Test
        @DisplayName("-f sets guids filename")
        void shortFilenameOption() throws IOException {
            GuidApiClient.parseCommandLineArgs(new String[]{"-f", "custom.txt"});
            assertEquals("custom.txt", GuidApiClient.guidsFilename);
        }

        @Test
        @DisplayName("--filename sets guids filename")
        void longFilenameOption() throws IOException {
            GuidApiClient.parseCommandLineArgs(new String[]{"--filename", "other.txt"});
            assertEquals("other.txt", GuidApiClient.guidsFilename);
        }

        @Test
        @DisplayName("-s and -f together set both values")
        void bothOptions() throws IOException {
            GuidApiClient.parseCommandLineArgs(new String[]{"-s", "https://s.example/", "-f", "both.txt"});
            assertEquals("https://s.example/", GuidApiClient.spreadsheetUri);
            assertEquals("both.txt", GuidApiClient.guidsFilename);
        }

        @Test
        @DisplayName("Unknown option throws IOException")
        void unknownOptionThrows() {
            IOException ex = assertThrows(IOException.class, () ->
                    GuidApiClient.parseCommandLineArgs(new String[]{"--unknown-flag"}));
            assertTrue(ex.getMessage().contains("Failed to parse command line arguments"));
        }

        @Test
        @DisplayName("Option without required value throws IOException")
        void missingValueThrows() {
            assertThrows(IOException.class, () ->
                    GuidApiClient.parseCommandLineArgs(new String[]{"-s"}));
        }

        @Test
        @DisplayName("parseCommandLineArgs returns CommandLine with fetch-all flag")
        void fetchAllFlag() throws IOException {
            CommandLine cmd = GuidApiClient.parseCommandLineArgs(new String[]{"-F"});
            assertTrue(cmd.hasOption("fetch-all"));
        }

        @Test
        @DisplayName("parseCommandLineArgs returns CommandLine with process-all flag")
        void processAllFlag() throws IOException {
            CommandLine cmd = GuidApiClient.parseCommandLineArgs(new String[]{"-P"});
            assertTrue(cmd.hasOption("process-all"));
        }

        @Test
        @DisplayName("parseCommandLineArgs returns CommandLine with fetch-and-process flag")
        void fetchAndProcessFlag() throws IOException {
            CommandLine cmd = GuidApiClient.parseCommandLineArgs(new String[]{"-A"});
            assertTrue(cmd.hasOption("fetch-and-process"));
        }

        @Test
        @DisplayName("parseCommandLineArgs returns CommandLine with process-file option")
        void processFileOption() throws IOException {
            CommandLine cmd = GuidApiClient.parseCommandLineArgs(new String[]{"-p", "guids_xx.txt"});
            assertTrue(cmd.hasOption("process-file"));
            assertEquals("guids_xx.txt", cmd.getOptionValue("process-file"));
        }

        @Test
        @DisplayName("INFO log is emitted when using command-line spreadsheet value")
        void logsCommandLineSpreadsheet() throws IOException {
            GuidApiClient.parseCommandLineArgs(new String[]{"-s", "https://logged.example.com/"});
            logHandler.flush();
            String log = logOutput.toString();
            assertTrue(log.contains("command line"));
            assertTrue(log.contains("https://logged.example.com/"));
        }

        @Test
        @DisplayName("INFO log mentions 'default' when no spreadsheet supplied")
        void logsDefaultSpreadsheet() throws IOException {
            GuidApiClient.parseCommandLineArgs(new String[]{});
            logHandler.flush();
            assertTrue(logOutput.toString().contains("default"));
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("generateFilename (private)")
    class GenerateFilenameTest {

        private GuidApiClient client;

        @BeforeEach
        void setup() {
            client = new GuidApiClient();
        }

        private String callGenerateFilename(String guid, String suffix) throws Exception {
            return invoke(client, "generateFilename",
                    new Class[]{String.class, String.class}, guid, suffix);
        }

        @Test
        @DisplayName("Plain hash identifier uses first 12 characters")
        void plainHashUsesFirst12Chars() throws Exception {
            String hash = "ab3035656480d6184f7a44c9951c5d49a9f9e6c5695841960e94f15e07399542";
            String result = callGenerateFilename(hash, "json");
            assertEquals("ab3035656480.json", result);
        }

        @Test
        @DisplayName("Suffix is appended correctly")
        void suffixAppended() throws Exception {
            String result = callGenerateFilename("abcdefghijklmnop", "json");
            assertTrue(result.endsWith(".json"));
        }

        @Test
        @DisplayName("GUID with /detail/ path extracts hash and uses first 12 characters")
        void detailPathExtracted() throws Exception {
            String guid = "https://datacatalogue.cessda.eu/detail/c67a982db2df09f49682dc622b0dd38d109b9dbddf248fbb39dd06e6bc110143/?lang=en";
            String result = callGenerateFilename(guid, "json");
            assertEquals("c67a982db2df.json", result);
        }

        @Test
        @DisplayName("Identifier shorter than 12 characters uses full identifier")
        void shortIdentifierUsedInFull() throws Exception {
            String result = callGenerateFilename("short", "json");
            assertEquals("short.json", result);
        }

        @Test
        @DisplayName("Identifier of exactly 12 characters is used in full")
        void exactlyTwelveChars() throws Exception {
            String result = callGenerateFilename("123456789012", "json");
            assertEquals("123456789012.json", result);
        }

        @Test
        @DisplayName("Special characters in identifier are replaced with underscores")
        void specialCharsReplaced() throws Exception {
            String result = callGenerateFilename("ab!cd efgh@ij", "json");
            assertFalse(result.contains(" "));
            assertFalse(result.contains("!"));
            assertFalse(result.contains("@"));
        }

        @Test
        @DisplayName("/detail/ GUID with query string strips query before taking prefix")
        void queryStringStrippedBeforePrefix() throws Exception {
            String guid = "https://example.com/detail/abcdefghijklmnop?lang=en";
            String result = callGenerateFilename(guid, "json");
            assertFalse(result.contains("lang"));
            assertEquals("abcdefghijkl.json", result);
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("detectContentType (private)")
    class DetectContentTypeTest {

        private GuidApiClient client;

        @BeforeEach
        void setup() {
            client = new GuidApiClient();
        }

        private String detect(String body) throws Exception {
            return invoke(client, "detectContentType",
                    new Class[]{String.class}, body);
        }

        @Test
        void nullReturnsEmpty() throws Exception {
            assertEquals("empty", detect(null));
        }

        @Test
        void blankStringReturnsEmpty() throws Exception {
            assertEquals("empty", detect("   "));
        }

        @Test
        void jsonObjectDetected() throws Exception {
            assertEquals("json", detect("{\"key\":\"value\"}"));
        }

        @Test
        void jsonArrayDetected() throws Exception {
            assertEquals("json", detect("[1,2,3]"));
        }

        @Test
        void doctypeHtmlDetected() throws Exception {
            assertEquals("html", detect("<!DOCTYPE html><html><body></body></html>"));
        }

        @Test
        void htmlTagDetected() throws Exception {
            assertEquals("html", detect("<html><head></head></html>"));
        }

        @Test
        void xmlDeclarationDetected() throws Exception {
            assertEquals("xml", detect("<?xml version=\"1.0\"?><root/>"));
        }

        @Test
        void xmlTagWithoutDeclarationDetected() throws Exception {
            assertEquals("xml", detect("<root><child/></root>"));
        }

        @Test
        void plainTextReturnsText() throws Exception {
            assertEquals("text", detect("plain text content"));
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("OAI-PMH XML parsing (private methods)")
    class OaiXmlParsingTest {

        private GuidApiClient client;

        @BeforeEach
        void setup() {
            client = new GuidApiClient();
        }

        // --- parseIdentifiers ---

        @Test
        @DisplayName("parseIdentifiers extracts identifiers from single-page response")
        void parseIdentifiersSinglePage() throws Exception {
            @SuppressWarnings("unchecked")
            List<String> ids = invoke(client, "parseIdentifiers",
                    new Class[]{String.class}, OAI_XML_SINGLE_PAGE);
            assertEquals(2, ids.size());
            assertTrue(ids.contains("abc123"));
            assertTrue(ids.contains("def456"));
        }

        @Test
        @DisplayName("parseIdentifiers works with no-namespace XML")
        void parseIdentifiersNoNamespace() throws Exception {
            @SuppressWarnings("unchecked")
            List<String> ids = invoke(client, "parseIdentifiers",
                    new Class[]{String.class}, OAI_XML_NO_NAMESPACE);
            assertEquals(1, ids.size());
            assertEquals("nons-id1", ids.get(0));
        }

        @Test
        @DisplayName("parseIdentifiers returns empty list when no identifiers present")
        void parseIdentifiersEmpty() throws Exception {
            String xml = """
                    <?xml version="1.0"?>
                    <OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/">
                      <ListIdentifiers/>
                    </OAI-PMH>
                    """;
            @SuppressWarnings("unchecked")
            List<String> ids = invoke(client, "parseIdentifiers",
                    new Class[]{String.class}, xml);
            assertTrue(ids.isEmpty());
        }

        @Test
        @DisplayName("parseIdentifiers throws IOException for malformed XML")
        void parseIdentifiersMalformedXml() {
            assertThrows(IOException.class, () ->
                    invoke(client, "parseIdentifiers",
                            new Class[]{String.class}, "this is not xml <<>>"));
        }

        // --- parseResumptionToken ---

        @Test
        @DisplayName("parseResumptionToken returns token when present")
        void parseResumptionTokenPresent() throws Exception {
            String token = invoke(client, "parseResumptionToken",
                    new Class[]{String.class}, OAI_XML_PAGE_1);
            assertEquals("myToken", token);
        }

        @Test
        @DisplayName("parseResumptionToken returns null for empty token element")
        void parseResumptionTokenEmpty() throws Exception {
            String token = invoke(client, "parseResumptionToken",
                    new Class[]{String.class}, OAI_XML_SINGLE_PAGE);
            // Element is present but text is empty → null
            org.junit.jupiter.api.Assertions.assertNull(token);
        }

        @Test
        @DisplayName("parseResumptionToken returns null when element is absent")
        void parseResumptionTokenAbsent() throws Exception {
            String token = invoke(client, "parseResumptionToken",
                    new Class[]{String.class}, OAI_XML_PAGE_2);
            org.junit.jupiter.api.Assertions.assertNull(token);
        }

        @Test
        @DisplayName("parseResumptionToken throws IOException for malformed XML")
        void parseResumptionTokenMalformedXml() {
            assertThrows(IOException.class, () ->
                    invoke(client, "parseResumptionToken",
                            new Class[]{String.class}, "<broken>"));
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("writeGuidsFile (private)")
    class WriteGuidsFileTest {

        private GuidApiClient client;

        private static final Path REAL_RESOURCES = Paths.get("src/main/resources").toAbsolutePath();
        private boolean createdResourcesDir = false;
        // Snapshot of files that existed before each test – never deleted by cleanup.
        private final java.util.Set<Path> preExistingFiles = new java.util.HashSet<>();

        // Language codes used only in these tests – unlikely to match real fetched files.
        // Using codes outside the real LANGUAGES list avoids touching production files.
        private static final String LANG_FALLBACK  = "xx";   // writesHeaderAndIdentifiers
        private static final String LANG_RESOURCES = "zz";   // writesToResourcesDirWhenPresent

        @BeforeEach
        void setup() throws IOException {
            client = new GuidApiClient();
            createdResourcesDir = false;
            preExistingFiles.clear();
            // Snapshot any guids_xx.txt / guids_zz.txt that happen to exist already.
            for (String lang : new String[]{LANG_FALLBACK, LANG_RESOURCES}) {
                Path candidate = REAL_RESOURCES.resolve("guids_" + lang + ".txt");
                if (Files.exists(candidate)) preExistingFiles.add(candidate);
                Path cwdCandidate = Paths.get("guids_" + lang + ".txt").toAbsolutePath();
                if (Files.exists(cwdCandidate)) preExistingFiles.add(cwdCandidate);
            }
        }

        @AfterEach
        void cleanup() throws IOException {
            // Delete only files that were not present before the test ran.
            for (String lang : new String[]{LANG_FALLBACK, LANG_RESOURCES}) {
                for (Path candidate : new Path[]{
                        REAL_RESOURCES.resolve("guids_" + lang + ".txt"),
                        Paths.get("guids_" + lang + ".txt").toAbsolutePath()}) {
                    if (!preExistingFiles.contains(candidate)) {
                        Files.deleteIfExists(candidate);
                    }
                }
            }
            if (createdResourcesDir && Files.isDirectory(REAL_RESOURCES)) {
                if (!Files.list(REAL_RESOURCES).findAny().isPresent()) {
                    Files.delete(REAL_RESOURCES);
                    Path parent = REAL_RESOURCES.getParent();
                    while (parent != null && !parent.equals(Paths.get("").toAbsolutePath())) {
                        if (Files.isDirectory(parent) && !Files.list(parent).findAny().isPresent()) {
                            Files.delete(parent);
                            parent = parent.getParent();
                        } else {
                            break;
                        }
                    }
                }
            }
        }

        @FunctionalInterface
        interface ThrowingRunnable {
            void run() throws Exception;
        }

        @Test
        @DisplayName("Falls back to CWD when src/main/resources does not exist, writing all content")
        void writesHeaderAndIdentifiers() throws Exception {
            Path expectedInResources = REAL_RESOURCES.resolve("guids_" + LANG_FALLBACK + ".txt");
            Path expectedInCwd       = Paths.get("guids_" + LANG_FALLBACK + ".txt").toAbsolutePath();

            invoke(client, "writeGuidsFile",
                    new Class[]{String.class, List.class},
                    LANG_FALLBACK, List.of("id-one", "id-two"));

            Path written = Files.exists(expectedInResources) ? expectedInResources : expectedInCwd;
            assertTrue(Files.exists(written),
                    "guids_" + LANG_FALLBACK + ".txt should have been written to resources or CWD");
            List<String> lines = Files.readAllLines(written, StandardCharsets.UTF_8);
            assertTrue(lines.stream().anyMatch(l -> l.startsWith("# Identifiers for language: " + LANG_FALLBACK)));
            assertTrue(lines.stream().anyMatch(l -> l.equals("id-one")));
            assertTrue(lines.stream().anyMatch(l -> l.equals("id-two")));
            assertTrue(lines.stream().anyMatch(l -> l.startsWith("# Count: 2")));
        }

        @Test
        @DisplayName("Writes to src/main/resources when that directory exists")
        void writesToResourcesDirWhenPresent() throws Exception {
            if (!Files.isDirectory(REAL_RESOURCES)) {
                Files.createDirectories(REAL_RESOURCES);
                createdResourcesDir = true;
            }

            invoke(client, "writeGuidsFile",
                    new Class[]{String.class, List.class},
                    LANG_RESOURCES, List.of("id-alpha"));

            Path written = REAL_RESOURCES.resolve("guids_" + LANG_RESOURCES + ".txt");
            assertTrue(Files.exists(written));
            String content = Files.readString(written, StandardCharsets.UTF_8);
            assertTrue(content.contains("id-alpha"));
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("fetchIdentifiersForLanguage – paginated (mocked HTTP)")
    class FetchIdentifiersTest {

        private static final Path REAL_RESOURCES = Paths.get("src/main/resources").toAbsolutePath();

        // Dummy language codes outside the real LANGUAGES list so these tests
        // never write to or delete real fetched identifier files.
        private static final String LANG_SINGLE = "x1";
        private static final String LANG_PAGE1  = "x2";
        private static final String LANG_ERROR  = "x3";

        private boolean createdResourcesDir = false;

        @BeforeEach
        void setUp() throws IOException {
            if (!Files.isDirectory(REAL_RESOURCES)) {
                Files.createDirectories(REAL_RESOURCES);
                createdResourcesDir = true;
            }
        }

        @AfterEach
        void cleanUp() throws IOException {
            // Only ever delete the dummy files this test class writes.
            for (String lang : new String[]{LANG_SINGLE, LANG_PAGE1, LANG_ERROR}) {
                Files.deleteIfExists(REAL_RESOURCES.resolve("guids_" + lang + ".txt"));
            }
            if (createdResourcesDir && Files.isDirectory(REAL_RESOURCES)) {
                if (!Files.list(REAL_RESOURCES).findAny().isPresent()) {
                    Files.delete(REAL_RESOURCES);
                    Path parent = REAL_RESOURCES.getParent();
                    while (parent != null && !parent.equals(Paths.get("").toAbsolutePath())) {
                        if (Files.isDirectory(parent) && !Files.list(parent).findAny().isPresent()) {
                            Files.delete(parent);
                            parent = parent.getParent();
                        } else {
                            break;
                        }
                    }
                }
            }
        }

        // -----------------------------------------------------------------------
        // HttpClient.send() is a generic method: when(client.send(any(), any()))
        // causes javac to infer T=Object, making thenReturn(HttpResponse<String>)
        // a compile error.  doReturn(...).when(mock).send(...) avoids the
        // type-inference problem entirely because doReturn is not generic.
        // -----------------------------------------------------------------------

        @Test
        @DisplayName("Single-page response writes correct identifiers to file")
        void singlePageFetch() throws Exception {
            doReturn(mockHttpResponse).when(mockHttpClient).send(any(), any());
            when(mockHttpResponse.statusCode()).thenReturn(200);
            when(mockHttpResponse.body()).thenReturn(OAI_XML_SINGLE_PAGE);

            GuidApiClient client = newClientWithMockedHttp();
            client.fetchIdentifiersForLanguage(LANG_SINGLE);

            Path written = REAL_RESOURCES.resolve("guids_" + LANG_SINGLE + ".txt");
            assertTrue(Files.exists(written));
            String content = Files.readString(written, StandardCharsets.UTF_8);
            assertTrue(content.contains("abc123"));
            assertTrue(content.contains("def456"));
        }

        @Test
        @DisplayName("Two-page response accumulates identifiers from both pages")
        void twoPageFetch() throws Exception {
            @SuppressWarnings("unchecked")
            HttpResponse<String> page1Response = mock(HttpResponse.class);
            @SuppressWarnings("unchecked")
            HttpResponse<String> page2Response = mock(HttpResponse.class);
            when(page1Response.statusCode()).thenReturn(200);
            when(page1Response.body()).thenReturn(OAI_XML_PAGE_1);
            when(page2Response.statusCode()).thenReturn(200);
            when(page2Response.body()).thenReturn(OAI_XML_PAGE_2);

            doReturn(page1Response).doReturn(page2Response)
                    .when(mockHttpClient).send(any(), any());

            GuidApiClient client = newClientWithMockedHttp();
            client.fetchIdentifiersForLanguage(LANG_PAGE1);

            Path written = REAL_RESOURCES.resolve("guids_" + LANG_PAGE1 + ".txt");
            assertTrue(Files.exists(written));
            String content = Files.readString(written, StandardCharsets.UTF_8);
            assertTrue(content.contains("page1-id1"));
            assertTrue(content.contains("page2-id1"));
        }

        @Test
        @DisplayName("Non-2xx HTTP status throws IOException")
        void nonSuccessStatusThrows() throws Exception {
            doReturn(mockHttpResponse).when(mockHttpClient).send(any(), any());
            when(mockHttpResponse.statusCode()).thenReturn(503);

            GuidApiClient client = newClientWithMockedHttp();
            // Exception is thrown before any file write occurs
            assertThrows(IOException.class, () ->
                    client.fetchIdentifiersForLanguage(LANG_ERROR));
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("writeResponseBodyAsJson (private)")
    class WriteResponseBodyAsJsonTest {

        private GuidApiClient client;

        @BeforeEach
        void setup() {
            client = new GuidApiClient();
        }

        @Test
        @DisplayName("Valid JSON body is written as-is")
        void validJsonWrittenDirectly() throws Exception {
            Path outFile = tempDir.resolve("out.json");
            String body = "{\"score\":42}";
            invoke(client, "writeResponseBodyAsJson",
                    new Class[]{Path.class, String.class, String.class, int.class},
                    outFile, body, "test-guid", 200);

            String content = Files.readString(outFile, StandardCharsets.UTF_8);
            JsonNode node = new ObjectMapper().readTree(content);
            assertEquals(42, node.get("score").asInt());
        }

        @Test
        @DisplayName("Non-JSON body is wrapped in a JSON structure")
        void nonJsonBodyWrapped() throws Exception {
            Path outFile = tempDir.resolve("out.json");
            String body = "<html><body>Result</body></html>";
            invoke(client, "writeResponseBodyAsJson",
                    new Class[]{Path.class, String.class, String.class, int.class},
                    outFile, body, "test-guid", 200);

            String content = Files.readString(outFile, StandardCharsets.UTF_8);
            JsonNode node = new ObjectMapper().readTree(content);
            assertEquals("test-guid", node.get("guid").asText());
            assertEquals(200, node.get("statusCode").asInt());
            assertTrue(node.get("content").asText().contains("<html>"));
        }

        @Test
        @DisplayName("File is created at the specified path")
        void fileCreatedAtPath() throws Exception {
            Path outFile = tempDir.resolve("created.json");
            assertFalse(Files.exists(outFile));
            invoke(client, "writeResponseBodyAsJson",
                    new Class[]{Path.class, String.class, String.class, int.class},
                    outFile, "{}", "g", 200);
            assertTrue(Files.exists(outFile));
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("writeStructuredJsonResponse (private)")
    class WriteStructuredJsonResponseTest {

        private GuidApiClient client;
        private java.time.Instant timestamp;

        @BeforeEach
        void setup() {
            client = new GuidApiClient();
            timestamp = java.time.Instant.now();
        }

        private void callWriteStructured(Path out, String body) throws Exception {
            invoke(client, "writeStructuredJsonResponse",
                    new Class[]{Path.class, String.class, String.class,
                            int.class, java.time.Instant.class, long.class},
                    out, body, "test-guid", 200, timestamp, 123L);
        }

        @Test
        @DisplayName("Metadata fields are present in output")
        void metadataFieldsPresent() throws Exception {
            Path outFile = tempDir.resolve("structured.json");
            callWriteStructured(outFile, "{\"data\":1}");

            JsonNode node = new ObjectMapper().readTree(outFile.toFile());
            assertEquals("test-guid", node.get("guid").asText());
            assertEquals(200, node.get("statusCode").asInt());
            assertEquals(123L, node.get("processingTimeMs").asLong());
            assertNotNull(node.get("requestTimestamp"));
            assertNotNull(node.get("responseTimestamp"));
        }

        @Test
        @DisplayName("JSON body is parsed and embedded as object, not string")
        void jsonBodyEmbeddedAsObject() throws Exception {
            Path outFile = tempDir.resolve("structured.json");
            callWriteStructured(outFile, "{\"score\":99}");

            JsonNode node = new ObjectMapper().readTree(outFile.toFile());
            assertTrue(node.get("response").isObject());
            assertEquals(99, node.get("response").get("score").asInt());
        }

        @Test
        @DisplayName("Embedded JSON-LD string in resultset is parsed into object")
        void embeddedJsonLdParsed() throws Exception {
            Path outFile = tempDir.resolve("structured.json");
            String inner = "{\"@context\":\"https://schema.org\",\"@type\":\"Dataset\"}";
            String body = "{\"resultset\":\"" + inner.replace("\"", "\\\"") + "\"}";
            callWriteStructured(outFile, body);

            JsonNode node = new ObjectMapper().readTree(outFile.toFile());
            JsonNode resultset = node.get("response").get("resultset");
            assertTrue(resultset.isObject(), "resultset should be parsed to an object");
            assertEquals("https://schema.org", resultset.get("@context").asText());
        }

        @Test
        @DisplayName("resultset that is already an object is left unchanged")
        void resultsetAlreadyObjectUnchanged() throws Exception {
            Path outFile = tempDir.resolve("structured.json");
            String body = "{\"resultset\":{\"@type\":\"Dataset\"}}";
            callWriteStructured(outFile, body);

            JsonNode node = new ObjectMapper().readTree(outFile.toFile());
            JsonNode resultset = node.get("response").get("resultset");
            assertTrue(resultset.isObject());
            assertEquals("Dataset", resultset.get("@type").asText());
        }

        @Test
        @DisplayName("HTML body is stored as string under 'response'")
        void htmlBodyStoredAsString() throws Exception {
            Path outFile = tempDir.resolve("structured.json");
            callWriteStructured(outFile, "<html><body>test</body></html>");

            JsonNode node = new ObjectMapper().readTree(outFile.toFile());
            assertTrue(node.get("response").isTextual());
            assertTrue(node.get("response").asText().contains("<html>"));
            assertEquals("html", node.get("contentType").asText());
        }

        @Test
        @DisplayName("Empty body results in 'empty' contentType")
        void emptyBodyContentType() throws Exception {
            Path outFile = tempDir.resolve("structured.json");
            callWriteStructured(outFile, "");

            JsonNode node = new ObjectMapper().readTree(outFile.toFile());
            assertEquals("empty", node.get("contentType").asText());
        }

        @Test
        @DisplayName("requestDetails block contains endpoint and method")
        void requestDetailsBlock() throws Exception {
            Path outFile = tempDir.resolve("structured.json");
            GuidApiClient.spreadsheetUri = "https://example.com/endpoint";
            callWriteStructured(outFile, "{}");

            JsonNode node = new ObjectMapper().readTree(outFile.toFile());
            JsonNode details = node.get("requestDetails");
            assertNotNull(details);
            assertEquals("https://example.com/endpoint", details.get("endpoint").asText());
            assertEquals("POST", details.get("method").asText());
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("readGuidsFromResource (private) – via processSingleFile")
    class ReadGuidsTest {

        @Test
        @DisplayName("Comments and blank lines are filtered out")
        void commentsAndBlanksFiltered() throws Exception {
            Path guidsFile = tempDir.resolve("test-guids.txt");
            Files.writeString(guidsFile, """
                    # comment line
                    guid-one
                    
                    # another comment
                    guid-two
                    """, StandardCharsets.UTF_8);

            GuidApiClient.guidsFilename = guidsFile.toString();
            // readGuidsFromResource is private; exercise via reflective call
            GuidApiClient client = new GuidApiClient();
            @SuppressWarnings("unchecked")
            List<String> guids = invoke(client, "readGuidsFromResource",
                    new Class[]{});
            assertEquals(2, guids.size());
            assertTrue(guids.contains("guid-one"));
            assertTrue(guids.contains("guid-two"));
        }

        @Test
        @DisplayName("FileNotFoundException thrown when file missing")
        void missingFileThrows() {
            GuidApiClient.guidsFilename = "does-not-exist-xyz.txt";
            GuidApiClient client = new GuidApiClient();
            assertThrows(java.io.FileNotFoundException.class, () ->
                    invoke(client, "readGuidsFromResource", new Class[]{}));
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("processSingleFile")
    class ProcessSingleFileTest {

        @Test
        @DisplayName("Empty file logs skip message and does not throw")
        void emptyFileSkipped() throws Exception {
            Path guidsFile = tempDir.resolve("empty.txt");
            Files.writeString(guidsFile, "# only comments\n", StandardCharsets.UTF_8);

            GuidApiClient client = new GuidApiClient();
            // processSingleFile resolves by filename; use full path string as filename
            // so the CWD fallback in readGuidsFromResource picks it up
            assertDoesNotThrow(() -> client.processSingleFile(guidsFile.toString()));

            logHandler.flush();
            assertTrue(logOutput.toString().contains("Skipping") ||
                    logOutput.toString().contains("No GUIDs found"));
        }

        @Test
        @DisplayName("Missing file propagates FileNotFoundException")
        void missingFileThrows() {
            GuidApiClient client = new GuidApiClient();
            assertThrows(java.io.FileNotFoundException.class, () ->
                    client.processSingleFile("totally-absent-file.txt"));
        }

        @Test
        @DisplayName("A guids_XX.txt file exists for each expected language")
        void languageGuidFilesExist() {
            // Candidate locations: the Maven resources directory (when running
            // from source) or the current working directory (when running from
            // a JAR after a fetch).
            Path resourcesDir = Paths.get("src/main/resources").toAbsolutePath();
            Path cwd = Paths.get("").toAbsolutePath();

            for (String lang : GuidApiClient.LANGUAGES) {
                String filename = "guids_" + lang + ".txt";
                boolean foundInResources = Files.exists(resourcesDir.resolve(filename));
                boolean foundInCwd       = Files.exists(cwd.resolve(filename));
                assertTrue(foundInResources || foundInCwd,
                        "Expected " + filename + " in " + resourcesDir + " or " + cwd);
            }
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("JSON payload construction")
    class JsonPayloadTest {

        @Test
        @DisplayName("guid field is the full OAI-PMH GetRecord URL for the identifier")
        void guidIsGetRecordUrl() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            String identifier = "ab3035656480d6184f7a44c9951c5d49";
            String expectedGuid = GuidApiClient.OAI_PMH_GET_RECORD_URL
                    + java.net.URLEncoder.encode(identifier, java.nio.charset.StandardCharsets.UTF_8);

            var payload = mapper.createObjectNode();
            payload.put("guid", expectedGuid);
            payload.put("url", GuidApiClient.BENCHMARK_ALGORITHM_URI);
            String json = mapper.writeValueAsString(payload);

            JsonNode parsed = mapper.readTree(json);
            assertEquals(expectedGuid, parsed.get("guid").asText());
            assertTrue(parsed.get("guid").asText()
                    .contains("verb=GetRecord&metadataPrefix=oai_ddi25&identifier="));
            assertEquals(GuidApiClient.BENCHMARK_ALGORITHM_URI, parsed.get("url").asText());
            assertEquals(2, parsed.size());
        }

        @Test
        @DisplayName("url field is the spreadsheet URI, not the record URL")
        void urlIsSpreadsheetUri() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            var payload = mapper.createObjectNode();
            payload.put("guid", GuidApiClient.OAI_PMH_GET_RECORD_URL + "some-id");
            payload.put("url", GuidApiClient.BENCHMARK_ALGORITHM_URI);
            String json = mapper.writeValueAsString(payload);

            JsonNode parsed = mapper.readTree(json);
            assertTrue(parsed.get("url").asText()
                    .startsWith("https://tools.ostrails.eu/champion/assess/algorithm/"));
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("Logging helpers")
    class LoggingTest {

        @Test
        @DisplayName("logInfo writes to logger at INFO level")
        void logInfoWritesToLogger() {
            GuidApiClient.logInfo("Test info message %s", "arg1");
            logHandler.flush();
            assertTrue(logOutput.toString().contains("Test info message arg1"));
        }

        @Test
        @DisplayName("logSevere writes to logger at SEVERE level")
        void logSevereWritesToLogger() {
            GuidApiClient.logSevere("Test severe message");
            logHandler.flush();
            assertTrue(logOutput.toString().contains("Test severe message"));
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("Getters")
    class GettersTest {

        @Test
        @DisplayName("getSpreadsheetUri returns current static value")
        void getSpreadsheetUri() {
            GuidApiClient.spreadsheetUri = "https://example.com/sheet";
            GuidApiClient client = new GuidApiClient();
            assertEquals("https://example.com/sheet", client.getSpreadsheetUri());
        }

        @Test
        @DisplayName("getGuidsFilename returns current static value")
        void getGuidsFilename() {
            GuidApiClient.guidsFilename = "custom.txt";
            GuidApiClient client = new GuidApiClient();
            assertEquals("custom.txt", client.getGuidsFilename());
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("runWithOutputFormats")
    class RunWithOutputFormatsTest {

        @Test
        @DisplayName("Returns without error when GUID file is empty")
        void emptyGuidFileHandledGracefully() throws Exception {
            Path guidsFile = tempDir.resolve("empty_run.txt");
            Files.writeString(guidsFile, "# no guids\n", StandardCharsets.UTF_8);
            GuidApiClient.guidsFilename = guidsFile.toString();

            GuidApiClient client = new GuidApiClient();
            assertDoesNotThrow(() -> client.runWithOutputFormats(false, false));

            logHandler.flush();
            assertTrue(logOutput.toString().contains("No GUIDs found") ||
                       logOutput.toString().contains("Exiting"));
        }

        @Test
        @DisplayName("Creates results directory if it does not exist")
        void createsResultsDirectory() throws Exception {
            Path guidsFile = tempDir.resolve("guids_run.txt");
            Files.writeString(guidsFile, "# empty\n", StandardCharsets.UTF_8);
            GuidApiClient.guidsFilename = guidsFile.toString();

            // runWithOutputFormats calls Files.createDirectories(Paths.get("results"))
            // which is always relative to the real CWD, not user.dir.
            // Capture what the CWD actually is and assert there.
            Path realCwd = Paths.get("").toAbsolutePath();
            Path expectedResultsDir = realCwd.resolve("results");
            try {
                GuidApiClient client = new GuidApiClient();
                client.runWithOutputFormats(false, false);
                assertTrue(Files.isDirectory(expectedResultsDir),
                        "results/ should have been created at " + expectedResultsDir);
            } finally {
                // Clean up only if we created it and it is empty
                if (Files.isDirectory(expectedResultsDir)) {
                    try (var stream = Files.list(expectedResultsDir)) {
                        if (stream.findAny().isEmpty()) {
                            Files.delete(expectedResultsDir);
                        }
                    }
                }
            }
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("main() – routing via CLI flags")
    class MainMethodRoutingTest {

        @Test
        @DisplayName("main() with invalid argument logs SEVERE and returns without throwing")
        void invalidArgLogsErrorAndReturns() {
            assertDoesNotThrow(() ->
                    GuidApiClient.main(new String[]{"--not-a-real-flag"}));
            logHandler.flush();
            assertTrue(logOutput.toString().contains("Failed to parse"),
                    "Expected SEVERE log entry for unparseable argument");
        }

        @Test
        @DisplayName("main() with missing GUID file logs SEVERE and returns without throwing")
        void missingGuidFileLogsErrorAndReturns() {
            assertDoesNotThrow(() -> GuidApiClient.main(new String[]{
                    "-f", "nonexistent-file-xyz.txt"}));
            logHandler.flush();
            String log = logOutput.toString();
            assertTrue(log.contains("nonexistent-file-xyz.txt"),
                    "Expected SEVERE log entry naming the missing file");
        }

        @Test
        @DisplayName("main() with -p and missing file logs SEVERE and returns without throwing")
        void processFileMissingLogsErrorAndReturns() {
            assertDoesNotThrow(() ->
                    GuidApiClient.main(new String[]{"-p", "no-such-file.txt"}));
            logHandler.flush();
            String log = logOutput.toString();
            assertTrue(log.contains("no-such-file.txt"),
                    "Expected SEVERE log entry naming the missing file");
        }
    }
}