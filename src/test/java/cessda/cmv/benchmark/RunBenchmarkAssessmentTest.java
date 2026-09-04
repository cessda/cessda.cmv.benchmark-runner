/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package cessda.cmv.benchmark;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.UnrecognizedOptionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

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
        void parseArgsThrowsOnUnrecognisedOption() {
                assertThrows(UnrecognizedOptionException.class,
                                () -> RunBenchmarkAssessment.parseArgs(
                                                new String[] { "--no-such-option" }));
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