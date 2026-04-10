/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package cessda.cmv.benchmark;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link RunBenchmarkAssessment}.
 *
 * <p>HTTP calls are not made; all tests exercise file handling, path
 * helpers, CLI argument parsing, and error-file writing using
 * temporary directories.</p>
 */
class RunBenchmarkAssessmentTest {

    // ── Fixture ──────────────────────────────────────────────────────────────

    private RunBenchmarkAssessment client;

    @BeforeEach
    void setUp() {
        client = new RunBenchmarkAssessment(
                RunBenchmarkAssessment.BENCHMARK_ALGORITHM_URI);
    }

    @AfterEach
    void tearDown() {
        client = null;
    }

    // ── Constants ────────────────────────────────────────────────────────────

    @Test
    void benchmarkAlgorithmUriIsNotBlank() {
        assertFalse(RunBenchmarkAssessment.BENCHMARK_ALGORITHM_URI.isBlank(),
                "BENCHMARK_ALGORITHM_URI must not be blank");
    }

    @Test
    void benchmarkAlgorithmUriStartsWithHttps() {
        assertTrue(
                RunBenchmarkAssessment.BENCHMARK_ALGORITHM_URI.startsWith("https://"),
                "BENCHMARK_ALGORITHM_URI must be an HTTPS URL");
    }

    @Test
    void defaultGuidsFileIsNotBlank() {
        assertFalse(RunBenchmarkAssessment.DEFAULT_GUIDS_FILE.isBlank(),
                "DEFAULT_GUIDS_FILE must not be blank");
    }

    @Test
    void defaultGuidsFileEndsWithTxt() {
        assertTrue(RunBenchmarkAssessment.DEFAULT_GUIDS_FILE.endsWith(".txt"),
                "DEFAULT_GUIDS_FILE must end with .txt");
    }

    @Test
    void defaultSetsContainsTenLanguages() {
        assertEquals(10, RunBenchmarkAssessment.DEFAULT_SETS.length);
    }

    @Test
    void defaultSetsContainsExpectedLanguageCodes() {
        List<String> sets = List.of(RunBenchmarkAssessment.DEFAULT_SETS);
        assertAll(
                () -> assertTrue(sets.contains("de")),
                () -> assertTrue(sets.contains("en")),
                () -> assertTrue(sets.contains("fr")),
                () -> assertTrue(sets.contains("sl-SI"))
        );
    }

    // ── extractLangFromFilename (package-private via reflection or
    //    tested indirectly; exposed here via a helper shim) ────────────────────
    // Because extractLangFromFilename is private static we test its
    // contract through the observable behaviour of processSingleFile,
    // and we also call it via reflection for fine-grained coverage.

    @Test
    void extractLangFromFilenameReturnsCorrectCode() throws Exception {
        var method = RunBenchmarkAssessment.class
                .getDeclaredMethod("extractLangFromFilename", String.class);
        method.setAccessible(true);
        assertEquals("de",    method.invoke(null, "guids_de.txt"));
        assertEquals("sl-SI", method.invoke(null, "guids_sl-SI.txt"));
        assertEquals("en",    method.invoke(null, "guids_en.txt"));
    }

    @Test
    void extractLangFromFilenameReturnsNullForUnrecognisedPattern()
            throws Exception {
        var method = RunBenchmarkAssessment.class
                .getDeclaredMethod("extractLangFromFilename", String.class);
        method.setAccessible(true);
        assertNull(method.invoke(null, "something_else.txt"));
        assertNull(method.invoke(null, "guids_de.csv"));
    }

    // ── deriveSubdirectory ───────────────────────────────────────────────────

    @Test
    void deriveSubdirectoryStripsExtension() throws Exception {
        var method = RunBenchmarkAssessment.class
                .getDeclaredMethod("deriveSubdirectory", String.class);
        method.setAccessible(true);
        assertEquals("guids_de",    method.invoke(null, "guids_de.txt"));
        assertEquals("guids_sl-SI", method.invoke(null, "guids_sl-SI.txt"));
    }

    @Test
    void deriveSubdirectoryWithNoExtensionReturnsFilename() throws Exception {
        var method = RunBenchmarkAssessment.class
                .getDeclaredMethod("deriveSubdirectory", String.class);
        method.setAccessible(true);
        assertEquals("guids_de", method.invoke(null, "guids_de"));
    }

    @Test
    void deriveSubdirectoryWithNullReturnsUnknown() throws Exception {
        var method = RunBenchmarkAssessment.class
                .getDeclaredMethod("deriveSubdirectory", String.class);
        method.setAccessible(true);
        assertEquals("unknown", method.invoke(null, (Object) null));
    }

    @Test
    void deriveSubdirectoryWithBlankStringReturnsUnknown() throws Exception {
        var method = RunBenchmarkAssessment.class
                .getDeclaredMethod("deriveSubdirectory", String.class);
        method.setAccessible(true);
        assertEquals("unknown", method.invoke(null, "   "));
    }

    // ── resolveOutputDir ─────────────────────────────────────────────────────

    @Test
    void resolveOutputDirWithSubDirIncludesSubDir() throws Exception {
        var method = RunBenchmarkAssessment.class
                .getDeclaredMethod("resolveOutputDir", String.class);
        method.setAccessible(true);
        Path result = (Path) method.invoke(null, "guids_de");
        assertTrue(result.toString().endsWith("guids_de"),
                "Output dir must end with the subdir name");
        assertTrue(result.toString().contains("results"),
                "Output dir must be under 'results'");
    }

    @Test
    void resolveOutputDirWithNullSubDirPointsToResultsRoot()
            throws Exception {
        var method = RunBenchmarkAssessment.class
                .getDeclaredMethod("resolveOutputDir", String.class);
        method.setAccessible(true);
        Path result = (Path) method.invoke(null, (Object) null);
        assertEquals("results", result.toString());
    }

    @Test
    void resolveOutputDirWithBlankSubDirPointsToResultsRoot()
            throws Exception {
        var method = RunBenchmarkAssessment.class
                .getDeclaredMethod("resolveOutputDir", String.class);
        method.setAccessible(true);
        Path result = (Path) method.invoke(null, "   ");
        assertEquals("results", result.toString());
    }

    // ── processSingleFile: missing file ──────────────────────────────────────

    @Test
    void processSingleFileThrowsFileNotFoundForMissingFile() {
        assertThrows(FileNotFoundException.class,
                () -> client.processSingleFile("guids_nonexistent_zzz.txt"));
    }

    // ── processSingleFile: empty file (all comments) ─────────────────────────

    @Test
    void processSingleFileSkipsWhenAllLinesAreComments(@TempDir Path tempDir)
            throws Exception {
        Path guidFile = tempDir.resolve("guids_de.txt");
        Files.writeString(guidFile,
                "# comment line 1\n# comment line 2\n",
                StandardCharsets.UTF_8);

        // Make the file visible on the classpath by switching to tempDir —
        // easier here to rely on CWD fallback by naming file explicitly.
        // We can't change CWD in JVM, so we use the absolute path.
        RunBenchmarkAssessment localClient =
                new RunBenchmarkAssessment(RunBenchmarkAssessment.BENCHMARK_ALGORITHM_URI);

        // processSingleFile looks up by name in resources then CWD;
        // to keep this test hermetic we invoke the private readGuidsFromResource
        // by setting guidsFilename via reflection and checking the list is empty.
        var field = RunBenchmarkAssessment.class.getDeclaredField("guidsFilename");
        field.setAccessible(true);

        // Write a temp file reachable from the current dir is not reliable
        // cross-environment; instead verify via reflection on readGuidsFromResource.
        var readMethod = RunBenchmarkAssessment.class
                .getDeclaredMethod("readGuidsFromResource");
        readMethod.setAccessible(true);

        field.set(localClient, guidFile.toAbsolutePath().toString());

        @SuppressWarnings("unchecked")
        List<String> guids = (List<String>) readMethod.invoke(localClient);
        assertTrue(guids.isEmpty(),
                "Comment-only file must produce an empty GUID list");
    }

    // ── readGuidsFromResource: filters blank lines and comments ───────────────

    @Test
    void readGuidsFromResourceFiltersBlankAndCommentLines(@TempDir Path tempDir)
            throws Exception {
        Path guidFile = tempDir.resolve("guids_test.txt");
        Files.writeString(guidFile,
                "# header\n"
                + "\n"
                + "https://example.org/oai?verb=GetRecord&identifier=a1\n"
                + "  \n"
                + "# another comment\n"
                + "https://example.org/oai?verb=GetRecord&identifier=b2\n",
                StandardCharsets.UTF_8);

        RunBenchmarkAssessment localClient =
                new RunBenchmarkAssessment(RunBenchmarkAssessment.BENCHMARK_ALGORITHM_URI);

        var field = RunBenchmarkAssessment.class.getDeclaredField("guidsFilename");
        field.setAccessible(true);
        field.set(localClient, guidFile.toAbsolutePath().toString());

        var readMethod = RunBenchmarkAssessment.class
                .getDeclaredMethod("readGuidsFromResource");
        readMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> guids = (List<String>) readMethod.invoke(localClient);

        assertEquals(2, guids.size());
        assertTrue(guids.get(0).contains("identifier=a1"));
        assertTrue(guids.get(1).contains("identifier=b2"));
    }

    // ── parseArgs ────────────────────────────────────────────────────────────

    @Test
    void parseArgsWithNoArgumentsReturnsEmptyCommandLine() throws IOException {
        CommandLine cmd = RunBenchmarkAssessment.parseArgs(new String[]{});
        assertFalse(cmd.hasOption("process-all"));
        assertFalse(cmd.hasOption("process-file"));
        assertFalse(cmd.hasOption("guid"));
    }

    @Test
    void parseArgsRecognisesProcessAllShortOption() throws IOException {
        CommandLine cmd = RunBenchmarkAssessment.parseArgs(new String[]{"-P"});
        assertTrue(cmd.hasOption("process-all"));
    }

    @Test
    void parseArgsRecognisesProcessAllLongOption() throws IOException {
        CommandLine cmd = RunBenchmarkAssessment.parseArgs(
                new String[]{"--process-all"});
        assertTrue(cmd.hasOption("process-all"));
    }

    @Test
    void parseArgsRecognisesProcessFileShortOption() throws IOException {
        CommandLine cmd = RunBenchmarkAssessment.parseArgs(
                new String[]{"-p", "guids_de.txt"});
        assertTrue(cmd.hasOption("process-file"));
        assertEquals("guids_de.txt", cmd.getOptionValue("process-file"));
    }

    @Test
    void parseArgsRecognisesGuidShortOption() throws IOException {
        String url = "https://example.org/oai?verb=GetRecord&identifier=x";
        CommandLine cmd = RunBenchmarkAssessment.parseArgs(
                new String[]{"-g", url});
        assertTrue(cmd.hasOption("guid"));
        assertEquals(url, cmd.getOptionValue("guid"));
    }

    @Test
    void parseArgsRecognisesSpreadsheetShortOption() throws IOException {
        CommandLine cmd = RunBenchmarkAssessment.parseArgs(
                new String[]{"-s", "https://custom.example.org/champion"});
        assertEquals("https://custom.example.org/champion",
                cmd.getOptionValue("spreadsheet"));
    }

    @Test
    void parseArgsRecognisesFilenameShortOption() throws IOException {
        CommandLine cmd = RunBenchmarkAssessment.parseArgs(
                new String[]{"-f", "guids_en.txt"});
        assertEquals("guids_en.txt", cmd.getOptionValue("filename"));
    }

    @Test
    void parseArgsThrowsOnUnrecognisedOption() {
        assertThrows(IOException.class,
                () -> RunBenchmarkAssessment.parseArgs(
                        new String[]{"--no-such-option"}));
    }

    // ── Logging helpers ──────────────────────────────────────────────────────

    @Test
    void logInfoDoesNotThrowForPlainMessage() {
        assertDoesNotThrow(
                () -> RunBenchmarkAssessment.logInfo("test message"));
    }

    @Test
    void logInfoDoesNotThrowForFormattedMessage() {
        assertDoesNotThrow(
                () -> RunBenchmarkAssessment.logInfo("count: %d", 7));
    }

    @Test
    void logSevereDoesNotThrowForPlainMessage() {
        assertDoesNotThrow(
                () -> RunBenchmarkAssessment.logSevere("severe"));
    }

    @Test
    void logSevereDoesNotThrowForFormattedMessage() {
        assertDoesNotThrow(
                () -> RunBenchmarkAssessment.logSevere("err: %s", "detail"));
    }

    // ── Constructor ──────────────────────────────────────────────────────────

    @Test
    void constructorWithCustomUriDoesNotThrow() {
        assertDoesNotThrow(() ->
                new RunBenchmarkAssessment("https://custom.example.org/api"));
    }

    // ── Parameterised: default sets match GetOaiPmhIdentifiers ───────────────

    @ParameterizedTest
    @ValueSource(strings = {"de", "el", "en", "fi", "fr", "hr", "nl",
                            "sl", "sl-SI", "sv"})
    void defaultSetsMatchGetOaiPmhIdentifiersDefaults(String lang) {
        List<String> runSets  = List.of(RunBenchmarkAssessment.DEFAULT_SETS);
        List<String> fetchSets = List.of(GetOaiPmhIdentifiers.DEFAULT_SETS);
        assertTrue(runSets.contains(lang),
                "RunBenchmarkAssessment.DEFAULT_SETS must contain " + lang);
        assertTrue(fetchSets.contains(lang),
                "GetOaiPmhIdentifiers.DEFAULT_SETS must contain " + lang);
    }
}