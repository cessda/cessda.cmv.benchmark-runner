/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package cessda.cmv.benchmark;

import cessda.cmv.benchmark.config.BenchmarkProperties;
import cessda.cmv.benchmark.tenant.TenantProperties;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.UnrecognizedOptionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RunBenchmarkAssessment}.
 *
 * <p>
 * HTTP calls are not made; all tests exercise file handling, path
 * helpers, CLI argument parsing, and error-file writing using
 * temporary directories.
 * </p>
 */
class RunBenchmarkAssessmentTest {

        // ── Fixture ──────────────────────────────────────────────────────────────

        private RunBenchmarkAssessment assessment;

        @BeforeEach
        void setUp() {
                assessment = new RunBenchmarkAssessment(null, null);
        }

        @AfterEach
        void tearDown() {
                assessment = null;
        }

        @Test
        void defaultSetsContainsTenSets() {
                assertEquals(10, RunBenchmarkAssessment.DEFAULT_SETS.length);
        }

        @Test
        void defaultSetsContainsExpectedSetCodes() {
                List<String> sets = List.of(RunBenchmarkAssessment.DEFAULT_SETS);
                assertAll(
                                () -> assertTrue(sets.contains("de")),
                                () -> assertTrue(sets.contains("en")),
                                () -> assertTrue(sets.contains("fr")),
                                () -> assertTrue(sets.contains("sl-SI")));
        }

        // ── deriveSubdirectory ───────────────────────────────────────────────────

        @Test
        void deriveSubdirectoryStripsExtension() {
                assertEquals(Path.of("guids_de"), RunBenchmarkAssessment.deriveSubdirectory(Path.of("guids_de.txt")));
                assertEquals(Path.of("guids_sl-SI"), RunBenchmarkAssessment.deriveSubdirectory(Path.of("guids_sl-SI.txt")));
        }

        @Test
        void deriveSubdirectoryWithNoExtensionReturnsFilename() {
                var testPath = Path.of("guids_de");
                assertEquals(testPath, RunBenchmarkAssessment.deriveSubdirectory(testPath));
        }

        @Test
        void deriveSubdirectoryWithRootThrowsException() {
                var testPath = Path.of("/");
                assertThrows(IllegalArgumentException.class, () -> RunBenchmarkAssessment.deriveSubdirectory(testPath));
        }

        // ── resolveOutputDir ─────────────────────────────────────────────────────

        @Test
        void resolveOutputDirWithSubDirIncludesSubDir() {
                Path result = assessment.resolveOutputDir(Path.of("guids_de"));
                assertTrue(result.endsWith("guids_de"),
                        "Output dir must end with the subdir name");
                assertTrue(result.toString().contains("results"),
                        "Output dir must be under 'results'");
        }

        @Test
        void resolveOutputDirWithNullSubDirPointsToResultsRoot() {
                Path result = assessment.resolveOutputDir(null);
                assertEquals(Path.of("results"), result);
        }

        @Test
        void resolveOutputDirWithAbsoluteSubDirThrows() {
                var absolutePath = Path.of("").toAbsolutePath();
                assertThrows(IllegalArgumentException.class, () -> assessment.resolveOutputDir(absolutePath));
        }

        // ── processSingleFile: empty file (all comments) ─────────────────────────

        @Test
        void processSingleFileSkipsWhenAllLinesAreComments(@TempDir Path tempDir)
                throws Exception {
                Path guidFile = tempDir.resolve("guids_de.txt");
                Files.writeString(guidFile,
                        "# comment line 1\n# comment line 2\n",
                        StandardCharsets.UTF_8);

                RunBenchmarkAssessment localClient = new RunBenchmarkAssessment(assessment.getSpreadsheetUri(),
                        assessment.getChampionUri());

                List<String> guids = localClient.readGuidsFromResource(guidFile);
                assertTrue(guids.isEmpty(), "Comment-only file must produce an empty GUID list");
        }

        // ── processSingleFile: missing file ──────────────────────────────────────

        @Test
        void processSingleFileThrowsFileNotFoundForMissingFile() {
                assertThrows(NoSuchFileException.class,
                        () -> assessment.processSingleFile(Path.of("guids_nonexistent_zzz.txt")));
        }

        // ── parseArgs ────────────────────────────────────────────────────────────

        @Test
        void parseArgsWithNoArgumentsReturnsEmptyCommandLine() throws ParseException {
                CommandLine cmd = RunBenchmarkAssessment.parseArgs(new String[] {});
                assertFalse(cmd.hasOption("process-all"));
                assertFalse(cmd.hasOption("process-file"));
                assertFalse(cmd.hasOption("guid"));
        }

        @Test
        void parseArgsRecognisesProcessAllShortOption() throws ParseException {
                CommandLine cmd = RunBenchmarkAssessment.parseArgs(new String[] { "-P" });
                assertTrue(cmd.hasOption("process-all"));
        }

        @Test
        void parseArgsRecognisesProcessAllLongOption() throws ParseException {
                CommandLine cmd = RunBenchmarkAssessment.parseArgs(
                                new String[] { "--process-all" });
                assertTrue(cmd.hasOption("process-all"));
        }

        @Test
        void parseArgsRecognisesProcessFileShortOption() throws ParseException {
                CommandLine cmd = RunBenchmarkAssessment.parseArgs(
                                new String[] { "-p", "guids_de.txt" });
                assertTrue(cmd.hasOption("process-file"));
                assertEquals("guids_de.txt", cmd.getOptionValue("process-file"));
        }

        @Test
        void parseArgsRecognisesGuidShortOption() throws ParseException {
                String url = "https://example.org/oai?verb=GetRecord&identifier=x";
                CommandLine cmd = RunBenchmarkAssessment.parseArgs(
                                new String[] { "-g", url });
                assertTrue(cmd.hasOption("guid"));
                assertEquals(url, cmd.getOptionValue("guid"));
        }

        @Test
        void parseArgsRecognisesSpreadsheetShortOption() throws ParseException {
                CommandLine cmd = RunBenchmarkAssessment.parseArgs(
                                new String[] { "-s", "https://custom.example.org/spreadsheet" });
                assertEquals("https://custom.example.org/spreadsheet",
                                cmd.getOptionValue("spreadsheetUri"));
        }

        @Test
        void parseArgsRecognisesFilenameShortOption() throws ParseException {
                CommandLine cmd = RunBenchmarkAssessment.parseArgs(
                                new String[] { "-f", "guids_en.txt" });
                assertEquals("guids_en.txt", cmd.getOptionValue("filename"));
        }

        @Test
        void parseArgsRecognisesTenantShortOption() throws ParseException {
                CommandLine cmd = RunBenchmarkAssessment.parseArgs(
                                new String[] { "-t", "cessda" });
                assertTrue(cmd.hasOption("tenant"));
                assertEquals("cessda", cmd.getOptionValue("tenant"));
        }

        @Test
        void parseArgsRecognisesTenantLongOption() throws ParseException {
                CommandLine cmd = RunBenchmarkAssessment.parseArgs(
                                new String[] { "--tenant", "oxford" });
                assertTrue(cmd.hasOption("tenant"));
                assertEquals("oxford", cmd.getOptionValue("tenant"));
        }

        @Test
        void parseArgsWithNoTenantOptionLeavesTenantAbsent() throws ParseException {
                CommandLine cmd = RunBenchmarkAssessment.parseArgs(
                                new String[] { "-s", "https://custom.example.org/spreadsheet" });
                assertFalse(cmd.hasOption("tenant"));
        }

        @Test
        void parseArgsThrowsOnUnrecognisedOption() {
                assertThrows(UnrecognizedOptionException.class,
                                () -> RunBenchmarkAssessment.parseArgs(
                                                new String[] { "--no-such-option" }));
        }

        // ── resolveTenant ────────────────────────────────────────────────────────
        // Exercises the same tenant-scoped resolution formula main() uses for
        // -t/--tenant, without booting a Spring context: algorithm/runner from
        // tenants.config.<tenantId> (with legacy alias fallback), and
        // data/results directories scoped under {data,results}-dir/<tenantId>/,
        // mirroring BenchmarkService's resolution for the REST API.

        private static BenchmarkProperties benchmarkPropertiesWithDirs(String dataDir, String resultsDir) {
                return new BenchmarkProperties(Path.of(dataDir), Path.of(resultsDir), null, null, null);
        }

        private static TenantProperties tenantPropertiesWith(String tenantId,
                        TenantProperties.TenantConfig config) {
                TenantProperties props = new TenantProperties();
                props.setConfig(Map.of(tenantId, config));
                return props;
        }

        @Test
        void resolveTenantReturnsNullForUnconfiguredTenant() {
                TenantProperties tenantProperties = new TenantProperties();
                BenchmarkProperties benchmarkProperties = benchmarkPropertiesWithDirs("./guids", "./results");

                RunBenchmarkAssessment.TenantResolution resolution = RunBenchmarkAssessment.resolveTenant(
                                tenantProperties, benchmarkProperties, "no-such-tenant");

                assertNull(resolution);
        }

        @Test
        void resolveTenantUsesTenantAlgorithmAndRunner() {
                TenantProperties.TenantConfig config = new TenantProperties.TenantConfig();
                config.setAlgorithm(URI.create("https://example.org/algorithm"));
                config.setRunner(URI.create("https://example.org/runner"));
                config.setTitle("Example · Assessment Results");
                config.setFooter("Example FAIR Benchmark Dashboard");

                TenantProperties tenantProperties = tenantPropertiesWith("example", config);
                BenchmarkProperties benchmarkProperties = benchmarkPropertiesWithDirs("./guids", "./results");

                RunBenchmarkAssessment.TenantResolution resolution = RunBenchmarkAssessment.resolveTenant(
                                tenantProperties, benchmarkProperties, "example");

                assertAll(
                                () -> assertEquals(URI.create("https://example.org/algorithm"), resolution.algorithm()),
                                () -> assertEquals(URI.create("https://example.org/runner"), resolution.runner()));
        }

        @Test
        void resolveTenantFallsBackToLegacyAliasFields() {
                TenantProperties.TenantConfig config = new TenantProperties.TenantConfig();
                config.setSpreadsheetUri(URI.create("https://example.org/legacy-algorithm"));
                config.setChampionUri(URI.create("https://example.org/legacy-runner"));
                config.setTitle("Legacy · Assessment Results");
                config.setFooter("Legacy FAIR Benchmark Dashboard");

                TenantProperties tenantProperties = tenantPropertiesWith("legacy", config);
                BenchmarkProperties benchmarkProperties = benchmarkPropertiesWithDirs("./guids", "./results");

                RunBenchmarkAssessment.TenantResolution resolution = RunBenchmarkAssessment.resolveTenant(
                                tenantProperties, benchmarkProperties, "legacy");

                assertAll(
                                () -> assertEquals(URI.create("https://example.org/legacy-algorithm"), resolution.algorithm()),
                                () -> assertEquals(URI.create("https://example.org/legacy-runner"), resolution.runner()));
        }

        @Test
        void resolveTenantScopesDataAndResultsDirsUnderTenantId() {
                TenantProperties.TenantConfig config = new TenantProperties.TenantConfig();
                config.setAlgorithm(URI.create("https://example.org/algorithm"));
                config.setRunner(URI.create("https://example.org/runner"));
                config.setTitle("CESSDA · Assessment Results");
                config.setFooter("CESSDA FAIR Benchmark Dashboard");

                TenantProperties tenantProperties = tenantPropertiesWith("cessda", config);
                BenchmarkProperties benchmarkProperties = benchmarkPropertiesWithDirs("./guids", "./results");

                RunBenchmarkAssessment.TenantResolution resolution = RunBenchmarkAssessment.resolveTenant(
                                tenantProperties, benchmarkProperties, "cessda");

                Path expectedDataDir = benchmarkProperties.getDataDir().resolve("cessda").normalize();
                Path expectedResultsDir = benchmarkProperties.getResultsDir().resolve("cessda").normalize();

                assertAll(
                                () -> assertEquals(expectedDataDir, resolution.dataDir()),
                                () -> assertEquals(expectedResultsDir, resolution.resultsDir()));
        }

        // ── Constructor ──────────────────────────────────────────────────────────

        @Test
        void constructorWithCustomUriDoesNotThrow() {
                assertDoesNotThrow(() -> new RunBenchmarkAssessment(
                        URI.create("https://custom.example.org/api"),
                        URI.create("https://custom.example.org/championUri")
                ));
        }

        // ── Parameterised: default sets match GetOaiPmhIdentifiers ───────────────

        @ParameterizedTest
        @ValueSource(strings = { "de", "el", "en", "fi", "fr", "hr", "nl",
                        "sl", "sl-SI", "sv" })
        void defaultSetsMatchGetOaiPmhIdentifiersDefaults(String set) {
                List<String> runSets = List.of(RunBenchmarkAssessment.DEFAULT_SETS);
                List<String> fetchSets = List.of(GetOaiPmhIdentifiers.DEFAULT_SETS);
                assertTrue(runSets.contains(set),
                                "RunBenchmarkAssessment.DEFAULT_SETS must contain " + set);
                assertTrue(fetchSets.contains(set),
                                "GetOaiPmhIdentifiers.DEFAULT_SETS must contain " + set);
        }
}