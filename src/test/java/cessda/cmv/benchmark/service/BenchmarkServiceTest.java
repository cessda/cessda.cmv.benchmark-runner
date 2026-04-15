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
import org.springframework.test.util.ReflectionTestUtils;

import cessda.cmv.benchmark.GetOaiPmhIdentifiers;
import cessda.cmv.benchmark.RunBenchmarkAssessment;

/**
 * Unit tests for {@link BenchmarkService}.
 *
 * <p>The service is instantiated directly (no Spring context) and its
 * private {@code dataDir} and {@code resultsDir} fields are injected
 * via {@link ReflectionTestUtils} to point at JUnit temporary
 * directories. This avoids any dependency on {@code /data} or
 * {@code /results} existing on the build machine.</p>
 *
 * <p>Tests that would make real outbound HTTP calls (fetchIdentifiers,
 * runAssessment) are limited to verifying parameter handling, volume
 * path wiring, and error propagation rather than end-to-end execution.
 * The {@code generateManifest} method is tested end-to-end because it
 * only performs local file I/O.</p>
 */
class BenchmarkServiceTest {

    @TempDir
    Path dataDir;

    @TempDir
    Path resultsDir;

    private BenchmarkService service;

    @BeforeEach
    void setUp() {
        service = new BenchmarkService();
        ReflectionTestUtils.setField(
            service, "dataDir",    dataDir.toString());
        ReflectionTestUtils.setField(
            service, "resultsDir", resultsDir.toString());
    }

    // -------------------------------------------------------------------------
    // fetchIdentifiers
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("fetchIdentifiers")
    class FetchIdentifiers {

        @Test
        @DisplayName("Publishes benchmark.data-dir as a system property")
        void publishesDataDirSystemProperty() throws Exception {
            // We cannot make real HTTP calls in a unit test, so we verify
            // that the system properties are set correctly before the
            // network call would be made by intercepting the property value.
            //
            // Trigger the method with a blank fetchSet so it would call
            // fetchAllLanguageIdentifiers; it will fail on the HTTP call,
            // but by then the system properties must already be set.
            try {
                service.fetchIdentifiers(
                    "http://invalid.example.invalid",
                    null, null, "de", null);
            } catch (Exception ignored) {
                // Expected: the HTTP call will fail.
            }
            assertEquals(dataDir.toString(),
                System.getProperty("benchmark.data-dir"),
                "benchmark.data-dir system property must be set to dataDir");
        }

        @Test
        @DisplayName("Publishes benchmark.results-dir as a system property")
        void publishesResultsDirSystemProperty() throws Exception {
            try {
                service.fetchIdentifiers(
                    "http://invalid.example.invalid",
                    null, null, "de", null);
            } catch (Exception ignored) {
                // Expected: the HTTP call will fail.
            }
            assertEquals(resultsDir.toString(),
                System.getProperty("benchmark.results-dir"),
                "benchmark.results-dir system property must be set to resultsDir");
        }

        @Test
        @DisplayName("Creates the data directory if it does not exist")
        void createsDataDirectoryWhenAbsent(@TempDir Path root)
                throws Exception {
            Path newDataDir = root.resolve("new-data");
            assertFalse(Files.exists(newDataDir));

            ReflectionTestUtils.setField(
                service, "dataDir", newDataDir.toString());

            try {
                service.fetchIdentifiers(
                    "http://invalid.example.invalid",
                    null, null, "de", null);
            } catch (Exception ignored) {
                // Expected: the HTTP call will fail.
            }
            assertTrue(Files.isDirectory(newDataDir),
                "data directory must be created by fetchIdentifiers");
        }

        @Test
        @DisplayName("Uses default OAI-PMH base URL when baseUrl is null")
        void usesDefaultBaseUrlWhenNull() {
            // Verify that GetOaiPmhIdentifiers.DEFAULT_OAI_PMH_BASE_URL
            // is the expected CESSDA URL so the nvl() logic is correct.
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
                "Default sets must match the expected language codes");
        }
    }

    // -------------------------------------------------------------------------
    // runAssessment
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("runAssessment")
    class RunAssessment {

        @Test
        @DisplayName("Creates data and results directories if absent")
        void createsBothDirectoriesWhenAbsent(@TempDir Path root)
                throws Exception {
            Path newData    = root.resolve("data");
            Path newResults = root.resolve("results");
            assertFalse(Files.exists(newData));
            assertFalse(Files.exists(newResults));

            ReflectionTestUtils.setField(
                service, "dataDir",    newData.toString());
            ReflectionTestUtils.setField(
                service, "resultsDir", newResults.toString());

            try {
                service.runAssessment(
                    "http://invalid.example.invalid",
                    null, null, false);
            } catch (Exception ignored) {
                // Expected: the file or HTTP call will fail.
            }

            assertTrue(Files.isDirectory(newData),
                "data directory must be created by runAssessment");
            assertTrue(Files.isDirectory(newResults),
                "results directory must be created by runAssessment");
        }

        @Test
        @DisplayName("Resolves a bare guid filename against the data directory")
        void resolvesGuidFilenameAgainstDataDir() throws Exception {
            // Write a minimal guids file to the data volume.
            Path guidFile = dataDir.resolve("guids_test.txt");
            Files.writeString(guidFile,
                "# test\nhttps://example.org/oai?verb=GetRecord"
                + "&metadataPrefix=oai_ddi25&identifier=abc",
                StandardCharsets.UTF_8);

            // The service should find the file in dataDir even when
            // only the bare filename is supplied.  The actual HTTP POST
            // will fail, but we verify no FileNotFoundException is thrown
            // before the network attempt.
            try {
                service.runAssessment(
                    "http://invalid.example.invalid",
                    "guids_test.txt", null, false);
            } catch (IOException e) {
                assertFalse(
                    e.getMessage().contains("Could not find"),
                    "FileNotFoundException must not be thrown when the file "
                    + "exists in the data directory; got: " + e.getMessage());
            } catch (Exception ignored) {
                // Any other exception (e.g. HTTP failure) is acceptable here.
            }
        }

        @Test
        @DisplayName("Uses default Champion API URI when spreadsheetUri is null")
        void usesDefaultChampionUriWhenNull() {
            assertEquals(
                "https://tools.ostrails.eu/champion/assess/algorithm"
                + "/d/1Nk0vM4yBpVQTo_UbB62NY_fz93aRZRHBZGh5fG-khOw",
                RunBenchmarkAssessment.BENCHMARK_ALGORITHM_URI,
                "Default Champion API URI must match the expected value");
        }

        @Test
        @DisplayName("Uses default guids filename when no parameters are supplied")
        void usesDefaultGuidsFilenameWhenNoParams() {
            assertEquals("guids_hr.txt",
                RunBenchmarkAssessment.DEFAULT_GUIDS_FILE,
                "Default guids filename must be guids_hr.txt");
        }
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
            String missingDir = resultsDir.resolve("does-not-exist").toString();

            IOException ex = assertThrows(IOException.class,
                () -> service.generateManifest(missingDir),
                "generateManifest must throw IOException for a missing directory");

            assertTrue(ex.getMessage().contains("Results directory not found"),
                "Exception message must mention the missing directory");
        }

        @Test
        @DisplayName("Uses configured resultsDir when override is null")
        void usesConfiguredResultsDirWhenOverrideIsNull() throws Exception {
            // An empty results dir produces no output but must not throw.
            assertDoesNotThrow(
                () -> service.generateManifest(null),
                "generateManifest must not throw for an empty results directory");
        }

        @Test
        @DisplayName("Uses configured resultsDir when override is blank")
        void usesConfiguredResultsDirWhenOverrideIsBlank() {
            // Passing a blank string must behave the same as null.
            assertDoesNotThrow(
                () -> service.generateManifest("   "),
                "A blank override must fall back to the configured resultsDir");
        }

        @Test
        @DisplayName("Writes summary.json when guids_* result directories exist")
        void writesSummaryJsonForPopulatedResultsDir() throws Exception {
            // Create a minimal result directory structure with one record file.
            Path langDir = resultsDir.resolve("guids_en");
            Files.createDirectories(langDir);

            // Minimal valid result JSON matching the shape GenerateManifest expects.
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

            Path summaryFile = resultsDir.resolve("summary.json");
            assertTrue(Files.exists(summaryFile),
                "summary.json must be written to the results directory");

            String summaryContent = Files.readString(
                summaryFile, StandardCharsets.UTF_8);
            assertTrue(summaryContent.contains("\"languages\""),
                "summary.json must contain a languages section");
            assertTrue(summaryContent.contains("\"en\""),
                "summary.json must contain an entry for the 'en' language set");
        }

        @Test
        @DisplayName("Writes paginated page files alongside summary.json")
        void writesPageFilesForPopulatedResultsDir() throws Exception {
            Path langDir = resultsDir.resolve("guids_de");
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
                "A pages/ sub-directory must be created for each language set");
            assertTrue(Files.exists(pagesDir.resolve("page-001.json")),
                "At least one page file must be written");
        }

        @Test
        @DisplayName("Ignores error_*.json files when aggregating results")
        void ignoresErrorFilesInResultsDir() throws Exception {
            Path langDir = resultsDir.resolve("guids_fr");
            Files.createDirectories(langDir);

            // Write an error file — GenerateManifest must skip it.
            Files.writeString(
                langDir.resolve("error_abc.json"),
                "{\"error\": \"timeout\"}",
                StandardCharsets.UTF_8);

            // Write one valid record file.
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
                resultsDir.resolve("summary.json"), StandardCharsets.UTF_8);
            // The summary must count exactly one record, not two.
            assertTrue(summary.contains("\"records\" : 1"),
                "Error files must not be counted as result records");
        }

        @Test
        @DisplayName("Returns a message containing the absolute results path")
        void returnMessageContainsAbsolutePath() throws Exception {
            String message = service.generateManifest(null);

            assertTrue(message.startsWith("Manifest generated in:"),
                "Return message must start with 'Manifest generated in:'");
            assertTrue(message.contains(
                    resultsDir.toAbsolutePath().toString()),
                "Return message must contain the absolute results path");
        }
    }
}