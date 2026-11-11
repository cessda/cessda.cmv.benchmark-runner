/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package eu.cessda.cmv.benchmark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GenerateManifest}, focused on the two maturity-level
 * computation models it supports: {@link MaturityConfig.Method#CHECKLIST}
 * (CESSDA's model, unchanged from before weighted-score support existed) and
 * {@link MaturityConfig.Method#WEIGHTED_SCORE} (Oxford's model).
 *
 * <p>{@link GenerateManifest} has no public accessor for a single record's
 * computed maturity level, so every test here drives the class the way it is
 * actually used: write one or more result files under a {@code guids_<set>/}
 * directory, call {@link GenerateManifest#run()}, then read the maturity
 * level back out of the written {@code pages/page-001.json} (per-record) or
 * {@code summary.json} (set-level distribution).</p>
 */
class GenerateManifestTest {

    @TempDir
    Path resultsDir;

    private Path setDir;

    private void writeRecord(String filename, String testedGuid, Map<String, double[]> testsPassWeight)
            throws Exception {
        // testsPassWeight: testId -> {passFlag (1/0), weight}
        StringBuilder tr = new StringBuilder();
        int i = 0;
        for (Map.Entry<String, double[]> e : testsPassWeight.entrySet()) {
            if (i++ > 0) tr.append(",");
            String result = e.getValue()[0] != 0.0 ? "pass" : "fail";
            tr.append("\"").append(e.getKey()).append("\": { \"result\": \"")
              .append(result).append("\", \"weight\": ").append(e.getValue()[1]).append(" }");
        }
        String json = """
                {
                  "testedguid": "%s",
                  "test_results": { %s },
                  "narratives": [],
                  "guidances": []
                }
                """.formatted(testedGuid, tr);
        Files.writeString(setDir.resolve(filename), json, StandardCharsets.UTF_8);
    }

    private int maturityOf(String set, String filenameBase) throws Exception {
        String summary = Files.readString(resultsDir.resolve("summary.json"), StandardCharsets.UTF_8);
        assertTrue(summary.contains("\"" + set + "\""), "summary.json must contain set " + set);

        // Individual record maturity is only present in the page files, not
        // summary.json (which only aggregates counts). Find it there.
        Path page = resultsDir.resolve("guids_" + set).resolve("pages").resolve("page-001.json");
        String pageContent = Files.readString(page, StandardCharsets.UTF_8);
        int idx = pageContent.indexOf("\"identifier\" : \"" + filenameBase + "\"");
        assertTrue(idx >= 0, "page-001.json must contain a record for " + filenameBase);
        int maturityIdx = pageContent.indexOf("\"maturity\"", idx);
        assertTrue(maturityIdx >= 0, "record must have a maturity field");
        int colon = pageContent.indexOf(':', maturityIdx);
        int comma = pageContent.indexOf(',', colon);
        int brace = pageContent.indexOf('}', colon);
        int end = (comma >= 0 && comma < brace) ? comma : brace;
        return Integer.parseInt(pageContent.substring(colon + 1, end).trim());
    }

    // ── CHECKLIST (CESSDA) ──────────────────────────────────────────────────

    @Test
    @DisplayName("main()'s default MaturityConfig.checklist(...) matches CHECKLIST behaviour")
    void defaultMaturityConfigIsChecklist() {
        MaturityConfig config =
                MaturityConfig.checklist(List.of(), List.of(), List.of());
        assertEquals(MaturityConfig.Method.CHECKLIST, config.getMethod());
        assertTrue(config.getCategoryMax().isEmpty());
        assertNull(config.getLevel1Threshold());
    }

    // ── WEIGHTED_SCORE (Oxford) ──────────────────────────────────────────────

    @Nested
    @DisplayName("CHECKLIST method (CESSDA)")
    class Checklist {

        @Test
        @DisplayName("Awards the highest level whose full test list was passed")
        void awardsHighestLevelFullyPassed() throws Exception {
            setDir = resultsDir.resolve("guids_de");
            Files.createDirectories(setDir);

            Map<String, String> fairMap = Map.of("A", "F", "B", "F", "C", "A", "D", "R");

            // Level lists are cumulative by convention, as CESSDA's real config is.
            MaturityConfig config = MaturityConfig.checklist(
                    List.of("A", "B"),
                    List.of("A", "B", "C"),
                    List.of("A", "B", "C", "D"));

            writeRecord("rec-level0.json", "https://x/?identifier=rec-level0",
                    Map.of("A", new double[]{1, 1.0}));
            writeRecord("rec-level1.json", "https://x/?identifier=rec-level1",
                    Map.of("A", new double[]{1, 1.0}, "B", new double[]{1, 1.0}));
            writeRecord("rec-level2.json", "https://x/?identifier=rec-level2",
                    Map.of("A", new double[]{1, 1.0}, "B", new double[]{1, 1.0}, "C", new double[]{1, 1.0}));
            writeRecord("rec-level3.json", "https://x/?identifier=rec-level3",
                    Map.of("A", new double[]{1, 1.0}, "B", new double[]{1, 1.0},
                           "C", new double[]{1, 1.0}, "D", new double[]{1, 1.0}));

            new GenerateManifest(resultsDir, fairMap, config).run();

            assertEquals(0, maturityOf("de", "rec-level0"));
            assertEquals(1, maturityOf("de", "rec-level1"));
            assertEquals(2, maturityOf("de", "rec-level2"));
            assertEquals(3, maturityOf("de", "rec-level3"));
        }

        @Test
        @DisplayName("A failed test in the level's list withholds that level, even if others pass")
        void failedRequiredTestWithholdsLevel() throws Exception {
            setDir = resultsDir.resolve("guids_de");
            Files.createDirectories(setDir);

            MaturityConfig config = MaturityConfig.checklist(
                    List.of("A", "B"), List.of(), List.of());

            writeRecord("rec.json", "https://x/?identifier=rec",
                    Map.of("A", new double[]{1, 1.0}, "B", new double[]{0, 0.0}));

            new GenerateManifest(resultsDir, Map.of(), config).run();

            assertEquals(0, maturityOf("de", "rec"));
        }

        @Test
        @DisplayName("An empty level test list is never awarded (not configured)")
        void emptyLevelListNeverAwarded() throws Exception {
            setDir = resultsDir.resolve("guids_de");
            Files.createDirectories(setDir);

            // Only level1 configured; level2/level3 default to empty lists.
            MaturityConfig config = MaturityConfig.checklist(
                    List.of("A"), List.of(), List.of());

            writeRecord("rec.json", "https://x/?identifier=rec",
                    Map.of("A", new double[]{1, 1.0}));

            new GenerateManifest(resultsDir, Map.of(), config).run();

            assertEquals(1, maturityOf("de", "rec"));
        }
    }

    // ── Backward compatibility ───────────────────────────────────────────────

    @Nested
    @DisplayName("WEIGHTED_SCORE method (Oxford)")
    class WeightedScore {

        private final Map<String, String> fairMap = Map.of(
                "F_TEST", "F",
                "A_TEST", "A",
                "I_TEST", "I",
                "R_TEST", "R");

        @Test
        @DisplayName("Averages only the categories present in categoryMax")
        void averagesOnlyConfiguredCategories() throws Exception {
            setDir = resultsDir.resolve("guids_bhf");
            Files.createDirectories(setDir);

            // Only F and A are configured; I and R must be excluded from the
            // average even though the record has test results for them.
            Map<String, Double> categoryMax = new LinkedHashMap<>();
            categoryMax.put("F", 10.0);
            categoryMax.put("A", 10.0);

            MaturityConfig config = MaturityConfig.weightedScore(
                    categoryMax, 25.0, 50.0, 75.0);

            // F earned 5/10 = 50%, A earned 2.5/10 = 25% -> avg (F,A only) = 37.5%
            // I and R have huge earned weight but must not affect the average.
            writeRecord("rec-mid.json", "https://x/?identifier=rec-mid", new LinkedHashMap<>(Map.of(
                    "F_TEST", new double[]{1, 5.0},
                    "A_TEST", new double[]{1, 2.5},
                    "I_TEST", new double[]{1, 1000.0},
                    "R_TEST", new double[]{1, 1000.0})));

            new GenerateManifest(resultsDir, fairMap, config).run();

            // 37.5% clears the 25% level1 threshold but not the 50% level2 one.
            assertEquals(1, maturityOf("bhf", "rec-mid"));
        }

        @Test
        @DisplayName("Full marks in every configured category reaches the top level")
        void fullMarksReachesTopLevel() throws Exception {
            setDir = resultsDir.resolve("guids_bhf");
            Files.createDirectories(setDir);

            Map<String, Double> categoryMax = new LinkedHashMap<>();
            categoryMax.put("F", 10.0);
            categoryMax.put("A", 10.0);

            MaturityConfig config = MaturityConfig.weightedScore(
                    categoryMax, 25.0, 50.0, 75.0);

            writeRecord("rec-full.json", "https://x/?identifier=rec-full", Map.of(
                    "F_TEST", new double[]{1, 10.0},
                    "A_TEST", new double[]{1, 10.0}));

            new GenerateManifest(resultsDir, fairMap, config).run();

            assertEquals(3, maturityOf("bhf", "rec-full"));
        }

        @Test
        @DisplayName("Zero earned weight in every configured category scores level 0")
        void zeroEarnedScoresLevelZero() throws Exception {
            setDir = resultsDir.resolve("guids_bhf");
            Files.createDirectories(setDir);

            Map<String, Double> categoryMax = new LinkedHashMap<>();
            categoryMax.put("F", 10.0);
            categoryMax.put("A", 10.0);

            MaturityConfig config = MaturityConfig.weightedScore(
                    categoryMax, 25.0, 50.0, 75.0);

            writeRecord("rec-zero.json", "https://x/?identifier=rec-zero", Map.of(
                    "F_TEST", new double[]{0, 0.0},
                    "A_TEST", new double[]{0, 0.0}));

            new GenerateManifest(resultsDir, fairMap, config).run();

            assertEquals(0, maturityOf("bhf", "rec-zero"));
        }

        @Test
        @DisplayName("A category with a configured max of zero is excluded, not treated as 0%")
        void zeroMaxCategoryIsExcludedNotZero() throws Exception {
            setDir = resultsDir.resolve("guids_bhf");
            Files.createDirectories(setDir);

            Map<String, Double> categoryMax = new LinkedHashMap<>();
            categoryMax.put("F", 10.0);
            categoryMax.put("I", 0.0); // must be excluded, not divide-by-zero or count as 0%

            MaturityConfig config = MaturityConfig.weightedScore(
                    categoryMax, 25.0, 50.0, 75.0);

            // F earned 10/10 = 100%. If I were wrongly averaged in as 0%, the
            // score would drop to 50% and only reach level2, not level3.
            writeRecord("rec.json", "https://x/?identifier=rec", Map.of(
                    "F_TEST", new double[]{1, 10.0},
                    "I_TEST", new double[]{1, 999.0}));

            assertDoesNotThrow(() -> new GenerateManifest(resultsDir, fairMap, config).run());

            assertEquals(3, maturityOf("bhf", "rec"));
        }

        @Test
        @DisplayName("A null threshold means that level can never be awarded")
        void nullThresholdNeverAwarded() throws Exception {
            setDir = resultsDir.resolve("guids_bhf");
            Files.createDirectories(setDir);

            Map<String, Double> categoryMax = new LinkedHashMap<>();
            categoryMax.put("F", 10.0);

            // level3Threshold left null -- Oxford has not supplied it yet.
            MaturityConfig config = MaturityConfig.weightedScore(
                    categoryMax, 25.0, 50.0, null);

            writeRecord("rec.json", "https://x/?identifier=rec", Map.of(
                    "F_TEST", new double[]{1, 10.0}));

            new GenerateManifest(resultsDir, fairMap, config).run();

            // 100% would clear a level3 threshold if one were configured, but
            // since it's null, level3 must never be reached -- level2 (the
            // highest configured threshold it clears) is the ceiling.
            assertEquals(2, maturityOf("bhf", "rec"));
        }
    }
}
