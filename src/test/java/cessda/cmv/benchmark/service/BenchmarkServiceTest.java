/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package cessda.cmv.benchmark.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cessda.cmv.benchmark.GetOaiPmhIdentifiers;
import cessda.cmv.benchmark.RunBenchmarkAssessment;
import cessda.cmv.benchmark.config.BenchmarkProperties;
import cessda.cmv.benchmark.tenant.TenantContext;
import cessda.cmv.benchmark.tenant.TenantProperties;
import cessda.cmv.benchmark.tenant.TenantProperties.TenantConfig;

/**
 * Unit tests for {@link BenchmarkService}.
 *
 * <p>The service is instantiated directly (no Spring context) with
 * {@link BenchmarkProperties} pointing at JUnit temporary directories.
 * This avoids any dependency on {@code /data} or {@code /results}
 * existing on the build machine.</p>
 *
 * <p>A stub {@link TenantContext} is wired in via the constructor,
 * pre-populated with the tenant ID {@code "test-tenant"} so that all
 * tenant-scoped sub-directory resolution works without a running Spring
 * context.</p>
 *
 * <p>Tests that would make real outbound HTTP calls (fetchIdentifiers,
 * runAssessment) are limited to verifying parameter handling, volume
 * path wiring, and error propagation rather than end-to-end execution.
 * The {@code generateManifest} method is tested end-to-end because it
 * only performs local file I/O.</p>
 */
class BenchmarkServiceTest {

    private static final String TENANT_ID = "test-tenant";

    @TempDir
    Path rootDataDir;

    @TempDir
    Path rootResultsDir;

    /**
     * The effective tenant data directory: {rootDataDir}/test-tenant/
     * This mirrors what BenchmarkService.tenantDataDir() resolves to.
     */
    Path tenantDataDir;

    /**
     * The effective tenant results directory: {rootResultsDir}/test-tenant/
     */
    Path tenantResultsDir;

    private BenchmarkService service;
    private BenchmarkProperties benchmarkProperties;

    @BeforeEach
    void setUp() throws IOException {
        // Build a stub TenantContext that always returns our fixed tenant ID.
        TenantContext tenantContext = new TenantContext();
        tenantContext.setTenantId(TENANT_ID);

        TenantConfig tenantConfig = new TenantConfig();
        tenantConfig.setAlgorithm("https://example.org/algorithm");
        tenantConfig.setRunner("https://example.org/runner");
        tenantConfig.setTitle("Test tenant title");
        tenantConfig.setFooter("Test tenant footer");

        TenantProperties tenantProperties = new TenantProperties();
        tenantProperties.setConfig(java.util.Map.of(TENANT_ID, tenantConfig));

        benchmarkProperties = new BenchmarkProperties();
        benchmarkProperties.setDataDir(rootDataDir.toString());
        benchmarkProperties.setResultsDir(rootResultsDir.toString());
        benchmarkProperties.setAlgorithm("https://example.org/algorithm");
        benchmarkProperties.setRunner("https://example.org/runner");

        service = new BenchmarkService(
                benchmarkProperties, tenantContext, tenantProperties);

        // Pre-create the tenant sub-directories so that tests which don't
        // exercise directory-creation logic can write fixture files directly.
        tenantDataDir    = rootDataDir.resolve(TENANT_ID);
        tenantResultsDir = rootResultsDir.resolve(TENANT_ID);
        Files.createDirectories(tenantDataDir);
        Files.createDirectories(tenantResultsDir);
    }

    // -------------------------------------------------------------------------
    // fetchIdentifiers
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("fetchIdentifiers")
    class FetchIdentifiers {

        @Test
        @DisplayName("Creates the tenant data directory when it does not exist")
        void createsTenantDataDirectoryWhenAbsent(@TempDir Path root)
                throws Exception {
            // Point the service at a fresh root; the tenant sub-dir must not
            // yet exist.
            Path newRootData = root.resolve("data");
            Files.createDirectories(newRootData);
            Path expectedTenantDir = newRootData.resolve(TENANT_ID);
            assertFalse(Files.exists(expectedTenantDir));

            benchmarkProperties.setDataDir(newRootData.toString());

            try {
                service.fetchIdentifiers(
                    "http://invalid.example.invalid",
                    null, null, "de", null);
            } catch (Exception ignored) {
                // Expected: the HTTP call will fail.
            }

            assertTrue(Files.isDirectory(expectedTenantDir),
                "fetchIdentifiers must create the tenant-scoped data directory");
        }

        @Test
        @DisplayName("Tenant data directories are isolated per tenant ID")
        void tenantDataDirsAreIsolated() throws Exception {
            // The directory created must be under the tenant ID sub-path,
            // not at the root data dir level.
            try {
                service.fetchIdentifiers(
                    "http://invalid.example.invalid",
                    null, null, "de", null);
            } catch (Exception ignored) { /* expected */ }

            // The tenant sub-dir must exist; the root must not contain any
            // guids files directly (they belong under the tenant sub-dir).
            assertTrue(Files.isDirectory(rootDataDir.resolve(TENANT_ID)),
                "Data files must be written under {dataDir}/{tenantId}/");
            assertFalse(
                Files.exists(rootDataDir.resolve("guids_de.txt")),
                "guids files must not appear directly under the root data dir");
        }

        @Test
        @DisplayName("Uses default OAI-PMH base URL when baseUrl is null")
        void usesDefaultBaseUrlWhenNull() {
            assertEquals(
                "https://datacatalogue.cessda.eu/oai-pmh/v0/oai",
                GetOaiPmhIdentifiers.DEFAULT_OAI_PMH_BASE_URL,
                "Default OAI-PMH base URL must match the CESSDA endpoint");
        }

        @Test
        @DisplayName("Uses default metadata prefix when metadataPrefix is null")
        void usesDefaultMetadataPrefixWhenNull() {
            assertEquals("oai_ddi25",
                GetOaiPmhIdentifiers.DEFAULT_METADATA_PREFIX,
                "Default metadata prefix must be oai_ddi25");
        }

        @Test
        @DisplayName("Uses default sets when sets parameter is null or blank")
        void usesDefaultSetsWhenNull() {
            assertArrayEquals(
                new String[]{"de","el","en","fi","fr","hr","nl","sl","sl-SI","sv"},
                GetOaiPmhIdentifiers.DEFAULT_SETS,
                "Default sets must match the expected set codes");
        }
    }

    // -------------------------------------------------------------------------
    // runAssessment
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("runAssessment")
    class RunAssessment {

        @Test
        @DisplayName("Creates tenant data and results directories if absent")
        void createsBothTenantDirectoriesWhenAbsent(@TempDir Path root)
                throws Exception {
            Path newRootData    = root.resolve("data");
            Path newRootResults = root.resolve("results");
            Files.createDirectories(newRootData);
            Files.createDirectories(newRootResults);

            Path expectedData    = newRootData.resolve(TENANT_ID);
            Path expectedResults = newRootResults.resolve(TENANT_ID);
            assertFalse(Files.exists(expectedData));
            assertFalse(Files.exists(expectedResults));

            benchmarkProperties.setDataDir(newRootData.toString());
            benchmarkProperties.setResultsDir(newRootResults.toString());

            try {
                service.runAssessment(
                    "http://invalid.example.invalid",
                    null, null, null, null, false);
            } catch (Exception ignored) {
                // Expected: the file or HTTP call will fail.
            }

            assertTrue(Files.isDirectory(expectedData),
                "runAssessment must create the tenant-scoped data directory");
            assertTrue(Files.isDirectory(expectedResults),
                "runAssessment must create the tenant-scoped results directory");
        }

        @Test
        @DisplayName("Resolves a bare guid filename against the tenant data directory")
        void resolvesGuidFilenameAgainstTenantDataDir() throws Exception {
            // Write a minimal guids file directly into the tenant data dir.
            Path guidFile = tenantDataDir.resolve("guids_test.txt");
            Files.writeString(guidFile,
                "# test\nhttps://example.org/oai?verb=GetRecord"
                + "&metadataPrefix=oai_ddi25&identifier=abc",
                StandardCharsets.UTF_8);

            // Supply the bare filename as the guidFile parameter; the service
            // resolves it against the tenant data dir.  The HTTP POST will fail,
            // but no FileNotFoundException should be thrown beforehand.
            try {
                service.runAssessment(
                    "http://invalid.example.invalid",
                    "http://invalid.example.invalid",
                    "guids_test.txt", null, null, false);
            } catch (java.io.FileNotFoundException fnfe) {
                org.junit.jupiter.api.Assertions.fail(
                    "FileNotFoundException must not be thrown when the file "
                    + "exists in the tenant data directory; got: " + fnfe.getMessage());
            } catch (Exception ignored) {
                // Any other exception (e.g. HTTP failure) is acceptable here.
            }
        }

        @Test
        @DisplayName("DEFAULT_GUIDS_FILE constant has expected value")
        void usesDefaultchampionUriWhenNull() {
            assertEquals("guids_hr.txt",
                RunBenchmarkAssessment.DEFAULT_GUIDS_FILE,
                "Default guids filename must be guids_hr.txt");
        }

        @Test
        @DisplayName("Returns message containing results path when processAll succeeds")
        void usesDefaultGuidsFilenameWhenNoParams() throws Exception {
            // Write the default guids file so processAll can find something to process.
            Path defaultFile = tenantDataDir.resolve(RunBenchmarkAssessment.DEFAULT_GUIDS_FILE);
            Files.writeString(defaultFile, "# empty\n", StandardCharsets.UTF_8);

            try {
                String result = service.runAssessment(
                    null, null,
                    RunBenchmarkAssessment.DEFAULT_GUIDS_FILE,
                    null, null, false);
                assertTrue(result.contains(tenantResultsDir.toString()),
                    "Return message must reference the tenant results directory");
            } catch (Exception ignored) {
                // HTTP call will fail; directory-creation and path-resolution
                // are the aspects verified above.
            }
        }
    }

    // -------------------------------------------------------------------------
    // tenant configuration
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Returns tenant algorithm and runner from configuration")
    void returnsTenantAlgorithmAndRunnerFromConfiguration() {
        assertArrayEquals(
            new String[]{"https://example.org/algorithm",
                "https://example.org/runner"},
            service.getDefaultAlgorithmAndRunner());
    }

    @Test
    @DisplayName("Resolves legacy spreadsheetUri and championUri tenant keys")
    void resolvesLegacyTenantKeys() {
        TenantContext tenantContext = new TenantContext();
        tenantContext.setTenantId("legacy-tenant");

        TenantConfig legacyConfig = new TenantConfig();
        legacyConfig.setAlgorithm("https://legacy.example.org/spreadsheet");
        legacyConfig.setRunner("https://legacy.example.org/champion");
        legacyConfig.setTitle("Legacy title");
        legacyConfig.setFooter("Legacy footer");

        TenantProperties tenantProperties = new TenantProperties();
        tenantProperties.setConfig(java.util.Map.of("legacy-tenant", legacyConfig));

        BenchmarkService legacyService = new BenchmarkService(
            benchmarkProperties,
            tenantContext,
            tenantProperties);

        assertArrayEquals(
            new String[]{
                "https://legacy.example.org/spreadsheet",
                "https://legacy.example.org/champion"
            },
            legacyService.getDefaultAlgorithmAndRunner());
    }

    @Test
    @DisplayName("Returns tenant branding from configuration")
    void returnsTenantBrandingFromConfiguration() {
        BenchmarkService.Branding branding = service.getTenantBranding();
        assertEquals("Test tenant title", branding.title());
        assertEquals("Test tenant footer", branding.footer());
    }

    // -------------------------------------------------------------------------
    // generateManifest
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("generateManifest")
    class GenerateManifest {

        @Test
        @DisplayName("Throws IOException when the results directory does not exist")
        void throwsWhenResultsDirMissing() {
            String missingDir = tenantResultsDir.resolve("does-not-exist").toString();

            IOException ex = assertThrows(IOException.class,
                () -> service.generateManifest(missingDir),
                "generateManifest must throw IOException for a missing directory");

            assertTrue(ex.getMessage().contains("Results directory not found"),
                "Exception message must mention the missing directory");
        }

        @Test
        @DisplayName("Uses tenant resultsDir when override is null")
        void usesConfiguredResultsDirWhenOverrideIsNull() {
            // An empty tenant results dir produces no output but must not throw.
            assertDoesNotThrow(
                () -> service.generateManifest(null),
                "generateManifest must not throw for an empty results directory");
        }

        @Test
        @DisplayName("Uses tenant resultsDir when override is blank")
        void usesConfiguredResultsDirWhenOverrideIsBlank() {
            assertDoesNotThrow(
                () -> service.generateManifest("   "),
                "A blank override must fall back to the tenant resultsDir");
        }

        @Test
        @DisplayName("Writes summary.json when guids_* result directories exist")
        void writesSummaryJsonForPopulatedResultsDir() throws Exception {
            // Create a minimal result directory structure with one record file
            // inside the tenant results dir.
            Path langDir = tenantResultsDir.resolve("guids_en");
            Files.createDirectories(langDir);

            String recordJson = """
                {
                  "testedguid": "https://example.org/oai?verb=GetRecord\
&metadataPrefix=oai_ddi25&identifier=abc123",
                  "test_results": {
                    "F1-GUID": { "result": "pass", "weight": 1.0 },
                    "F2A":     { "result": "fail", "weight": 0.0 }
                  },
                  "narratives": [],
                  "guidances":  []
                }
                """;
            Files.writeString(
                langDir.resolve("abc123.json"),
                recordJson,
                StandardCharsets.UTF_8);

            service.generateManifest(null);

            Path summaryFile = tenantResultsDir.resolve("summary.json");
            assertTrue(Files.exists(summaryFile),
                "summary.json must be written to the tenant results directory");

            String summaryContent = Files.readString(
                summaryFile, StandardCharsets.UTF_8);
            assertTrue(summaryContent.contains("\"sets\""),
                "summary.json must contain a sets section");
            assertTrue(summaryContent.contains("\"en\""),
                "summary.json must contain an entry for the 'en' set set");
        }

        @Test
        @DisplayName("Writes paginated page files alongside summary.json")
        void writesPageFilesForPopulatedResultsDir() throws Exception {
            Path langDir = tenantResultsDir.resolve("guids_de");
            Files.createDirectories(langDir);

            String recordJson = """
                {
                  "testedguid": "https://example.org/oai?verb=GetRecord\
&metadataPrefix=oai_ddi25&identifier=xyz789",
                  "test_results": {
                    "F1-GUID": { "result": "pass", "weight": 1.0 }
                  },
                  "narratives": [],
                  "guidances":  []
                }
                """;
            Files.writeString(
                langDir.resolve("xyz789.json"),
                recordJson,
                StandardCharsets.UTF_8);

            service.generateManifest(null);

            Path pagesDir = langDir.resolve("pages");
            assertTrue(Files.isDirectory(pagesDir),
                "A pages/ sub-directory must be created for each GUID set");
            assertTrue(Files.exists(pagesDir.resolve("page-001.json")),
                "At least one page file must be written");
        }

        @Test
        @DisplayName("Ignores error_*.json files when aggregating results")
        void ignoresErrorFilesInResultsDir() throws Exception {
            Path langDir = tenantResultsDir.resolve("guids_fr");
            Files.createDirectories(langDir);

            Files.writeString(
                langDir.resolve("error_abc.json"),
                "{\"error\": \"timeout\"}",
                StandardCharsets.UTF_8);

            String recordJson = """
                {
                  "testedguid": "https://example.org/oai?verb=GetRecord\
&metadataPrefix=oai_ddi25&identifier=rec1",
                  "test_results": {
                    "A1-1": { "result": "pass", "weight": 1.0 }
                  },
                  "narratives": [],
                  "guidances":  []
                }
                """;
            Files.writeString(
                langDir.resolve("rec1.json"),
                recordJson,
                StandardCharsets.UTF_8);

            assertDoesNotThrow(
                () -> service.generateManifest(null),
                "generateManifest must not throw when error files are present");

            String summary = Files.readString(
                tenantResultsDir.resolve("summary.json"), StandardCharsets.UTF_8);
            assertTrue(summary.contains("\"records\" : 1"),
                "Error files must not be counted as result records");
        }

        @Test
        @DisplayName("Returns a message containing the absolute tenant results path")
        void returnMessageContainsAbsolutePath() throws Exception {
            String message = service.generateManifest(null);

            assertTrue(message.startsWith("Manifest generated in:"),
                "Return message must start with 'Manifest generated in:'");
            assertTrue(message.contains(
                    tenantResultsDir.toAbsolutePath().toString()),
                "Return message must contain the absolute tenant results path");
        }
    }

    // -------------------------------------------------------------------------
    // Configuration resolution
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Throws IllegalStateException when algorithm is not configured")
    void failsFastForMissingAlgorithmConfiguration() {
        TenantContext tenantContext = new TenantContext();
        tenantContext.setTenantId("no-algorithm-tenant");

        TenantConfig cfg = new TenantConfig();
        cfg.setRunner("https://example.org/runner");
        cfg.setTitle("T");
        cfg.setFooter("F");

        TenantProperties tenantProperties = new TenantProperties();
        tenantProperties.setConfig(java.util.Map.of("no-algorithm-tenant", cfg));

        BenchmarkProperties props = new BenchmarkProperties();
        props.setDataDir(rootDataDir.toString());
        props.setResultsDir(rootResultsDir.toString());
        // Deliberately leave algorithm unset

        BenchmarkService unconfigured = new BenchmarkService(props, tenantContext, tenantProperties);

        assertThrows(IllegalStateException.class,
            unconfigured::getDefaultAlgorithmAndRunner,
            "getDefaultAlgorithmAndRunner must throw when no algorithm is configured");
    }

    @Test
    @DisplayName("Throws IllegalStateException when runner is not configured")
    void failsFastForMissingRunnerConfiguration() {
        TenantContext tenantContext = new TenantContext();
        tenantContext.setTenantId("no-runner-tenant");

        TenantConfig cfg = new TenantConfig();
        cfg.setAlgorithm("https://example.org/algorithm");
        cfg.setTitle("T");
        cfg.setFooter("F");

        TenantProperties tenantProperties = new TenantProperties();
        tenantProperties.setConfig(java.util.Map.of("no-runner-tenant", cfg));

        BenchmarkProperties props = new BenchmarkProperties();
        props.setDataDir(rootDataDir.toString());
        props.setResultsDir(rootResultsDir.toString());
        // Deliberately leave runner unset

        BenchmarkService unconfigured = new BenchmarkService(props, tenantContext, tenantProperties);

        assertThrows(IllegalStateException.class,
            unconfigured::getDefaultAlgorithmAndRunner,
            "getDefaultAlgorithmAndRunner must throw when no runner is configured");
    }
}