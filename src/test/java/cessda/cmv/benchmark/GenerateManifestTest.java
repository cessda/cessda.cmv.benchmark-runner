/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package cessda.cmv.benchmark;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link GenerateManifest}.
 *
 * <p>Tests cover FAIR category mapping, summary.json content,
 * page file creation, edge cases (empty directories, error files,
 * malformed JSON), and the overall run() contract.</p>
 */
class GenerateManifestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── fairCategory ─────────────────────────────────────────────────────────

    @ParameterizedTest(name = "fairCategory({0}) == {1}")
    @CsvSource({
            "F1-PID,     F",
            "F1-GUID,    F",
            "F2A,        F",
            "F2B,        F",
            "F4,         F",
            "A1-1,       A",
            "A1-2,       A",
            "I1-A,       I",
            "I2-A,       I",
            "R1-2-CPI,   R",
            "R1-3-CEK,   R",
            "R1-3-CTV,   R",
            "R1-3-DMOCV, R",
            "R1-3-DAUV,  R",
            "R1-3-DTMV,  R",
            "R1-3-DSPV,  R"
    })
    void fairCategoryReturnsCorrectCategoryForKnownIds(
            String testId, String expectedCategory) {
        assertEquals(expectedCategory.trim(),
                GenerateManifest.fairCategory(testId.trim()));
    }

    @ParameterizedTest(name = "fairCategory({0}) falls back to first char")
    @CsvSource({
            "F-CUSTOM, F",
            "A-OTHER,  A",
            "I-EXTRA,  I",
            "R-NEW,    R"
    })
    void fairCategoryFallsBackToFirstCharForUnknownIds(
            String testId, String expectedCategory) {
        assertEquals(expectedCategory.trim(),
                GenerateManifest.fairCategory(testId.trim()));
    }

    @Test
    void fairCategoryReturnsNullForUnrecognisedPrefix() {
        assertNull(GenerateManifest.fairCategory("X-UNKNOWN"));
    }

    @Test
    void fairCategoryReturnsNullForEmptyString() {
        assertNull(GenerateManifest.fairCategory(""));
    }

    @Test
    void fairCategoryTrimsWhitespaceBeforeMapping() {
        assertEquals("F", GenerateManifest.fairCategory("  F1-GUID  "));
    }

    // ── run: no guids_ subdirectories ────────────────────────────────────────

    @Test
    void runWithNoLanguageDirectoriesWritesSummaryWithEmptyLanguages(
            @TempDir Path tempDir) throws IOException {
        new GenerateManifest(tempDir).run();

        Path summary = tempDir.resolve("summary.json");
        assertTrue(Files.exists(summary),
                "summary.json must be created even when no language dirs exist");

        JsonNode root = MAPPER.readTree(summary.toFile());
        assertTrue(root.has("generated"), "summary must have 'generated' field");
        assertTrue(root.has("overall"),   "summary must have 'overall' field");
        assertTrue(root.has("languages"), "summary must have 'languages' field");
        assertEquals(0, root.path("languages").size(),
                "languages object must be empty when no guids_ dirs exist");
    }

    @Test
    void runIgnoresNonLanguageSubdirectories(@TempDir Path tempDir)
            throws IOException {
        Files.createDirectories(tempDir.resolve("other_dir"));
        Files.createDirectories(tempDir.resolve("not_a_lang"));

        new GenerateManifest(tempDir).run();

        JsonNode root = MAPPER.readTree(tempDir.resolve("summary.json").toFile());
        assertEquals(0, root.path("languages").size(),
                "Non-guids_ directories must not appear in languages");
    }

    // ── run: empty language directory ─────────────────────────────────────────

    @Test
    void runWithEmptyLanguageDirDoesNotAddLanguageToSummary(
            @TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("guids_de"));

        new GenerateManifest(tempDir).run();

        JsonNode langs = MAPPER.readTree(tempDir.resolve("summary.json").toFile())
                               .path("languages");
        assertFalse(langs.has("de"),
                "Empty language dir must not produce a languages entry");
    }

    // ── run: error_ files are skipped ────────────────────────────────────────

    @Test
    void runSkipsErrorFiles(@TempDir Path tempDir) throws IOException {
        Path langDir = tempDir.resolve("guids_en");
        Files.createDirectories(langDir);

        // Write one valid result and one error file
        Files.writeString(langDir.resolve("error_abc.json"),
                buildResultJson("https://example.org?identifier=abc",
                        "pass", 1.0),
                StandardCharsets.UTF_8);
        Files.writeString(langDir.resolve("valid_result.json"),
                buildResultJson("https://example.org?identifier=xyz",
                        "pass", 1.0),
                StandardCharsets.UTF_8);

        new GenerateManifest(tempDir).run();

        JsonNode langs = MAPPER.readTree(tempDir.resolve("summary.json").toFile())
                               .path("languages");
        assertTrue(langs.has("en"), "Language 'en' must appear in summary");
        assertEquals(1, langs.path("en").path("records").asInt(),
                "Only the non-error file must be counted");
    }

    // ── run: malformed JSON files are skipped ─────────────────────────────────

    @Test
    void runSkipsMalformedJsonFiles(@TempDir Path tempDir) throws IOException {
        Path langDir = tempDir.resolve("guids_fr");
        Files.createDirectories(langDir);

        Files.writeString(langDir.resolve("bad.json"),
                "{ this is not valid json }", StandardCharsets.UTF_8);
        Files.writeString(langDir.resolve("good.json"),
                buildResultJson("https://example.org?identifier=g1",
                        "fail", 0.0),
                StandardCharsets.UTF_8);

        new GenerateManifest(tempDir).run();

        JsonNode langs = MAPPER.readTree(tempDir.resolve("summary.json").toFile())
                               .path("languages");
        assertEquals(1, langs.path("fr").path("records").asInt(),
                "Malformed JSON file must be skipped; only valid file counted");
    }

    // ── run: pass / fail / indet counts ──────────────────────────────────────

    @Test
    void runAggregatesPassFailIndetCounts(@TempDir Path tempDir)
            throws IOException {
        Path langDir = tempDir.resolve("guids_de");
        Files.createDirectories(langDir);

        Files.writeString(langDir.resolve("r1.json"),
                buildResultJsonMultiTest("https://x?identifier=r1",
                        List.of("pass", "fail", "indeterminate")),
                StandardCharsets.UTF_8);

        new GenerateManifest(tempDir).run();

        JsonNode lang = MAPPER.readTree(tempDir.resolve("summary.json").toFile())
                              .path("languages").path("de");
        assertEquals(1, lang.path("records").asInt());
        assertEquals(1, lang.path("pass").asInt(),
                "One pass result expected");
        assertEquals(1, lang.path("fail").asInt(),
                "One fail result expected");
        assertEquals(1, lang.path("indet").asInt(),
                "One indeterminate result expected");
    }

    // ── run: overall aggregation across languages ─────────────────────────────

    @Test
    void runAggregatesOverallAcrossAllLanguages(@TempDir Path tempDir)
            throws IOException {
        for (String lang : List.of("de", "en")) {
            Path langDir = tempDir.resolve("guids_" + lang);
            Files.createDirectories(langDir);
            Files.writeString(langDir.resolve("r.json"),
                    buildResultJson("https://x?identifier=r_" + lang,
                            "pass", 1.0),
                    StandardCharsets.UTF_8);
        }

        new GenerateManifest(tempDir).run();

        JsonNode overall = MAPPER.readTree(tempDir.resolve("summary.json").toFile())
                                 .path("overall");
        assertEquals(2, overall.path("records").asInt(),
                "Overall record count must sum across both languages");
    }

    // ── run: FAIR category totals in summary ──────────────────────────────────

    @Test
    void runPopulatesFairCategoryTotalsInSummary(@TempDir Path tempDir)
            throws IOException {
        Path langDir = tempDir.resolve("guids_fi");
        Files.createDirectories(langDir);
        Files.writeString(langDir.resolve("r.json"),
                buildResultJsonForFairTest("https://x?identifier=fi1",
                        "F1-GUID", "pass"),
                StandardCharsets.UTF_8);

        new GenerateManifest(tempDir).run();

        JsonNode fair = MAPPER.readTree(tempDir.resolve("summary.json").toFile())
                              .path("languages").path("fi").path("fair");
        assertTrue(fair.has("F"), "FAIR summary must have 'F' category");
        assertEquals(1, fair.path("F").path("pass").asInt(),
                "F category pass count must be 1");
        assertEquals(1, fair.path("F").path("total").asInt(),
                "F category total count must be 1");
    }

    // ── run: page files are created ───────────────────────────────────────────

    @Test
    void runCreatesAtLeastOnePageFilePerNonEmptyLanguage(
            @TempDir Path tempDir) throws IOException {
        Path langDir = tempDir.resolve("guids_sv");
        Files.createDirectories(langDir);
        Files.writeString(langDir.resolve("r.json"),
                buildResultJson("https://x?identifier=sv1", "pass", 1.0),
                StandardCharsets.UTF_8);

        new GenerateManifest(tempDir).run();

        Path pagesDir = langDir.resolve("pages");
        assertTrue(Files.isDirectory(pagesDir),
                "pages/ subdirectory must be created");
        assertTrue(Files.list(pagesDir)
                        .anyMatch(p -> p.getFileName().toString()
                                        .matches("page-\\d{3}\\.json")),
                "At least one page-NNN.json file must exist");
    }

    @Test
    void runClearsStalePageFilesBeforeWriting(@TempDir Path tempDir)
            throws IOException {
        Path langDir = tempDir.resolve("guids_nl");
        Path pagesDir = langDir.resolve("pages");
        Files.createDirectories(pagesDir);

        // Write a stale page file
        Path stalePage = pagesDir.resolve("page-099.json");
        Files.writeString(stalePage, "[]", StandardCharsets.UTF_8);

        // Write one valid result file
        Files.writeString(langDir.resolve("r.json"),
                buildResultJson("https://x?identifier=nl1", "pass", 1.0),
                StandardCharsets.UTF_8);

        new GenerateManifest(tempDir).run();

        assertFalse(Files.exists(stalePage),
                "Stale page-099.json must be deleted before new pages are written");
    }

    @Test
    void runPageCountInSummaryMatchesActualPageFiles(@TempDir Path tempDir)
            throws IOException {
        Path langDir = tempDir.resolve("guids_hr");
        Files.createDirectories(langDir);
        Files.writeString(langDir.resolve("r.json"),
                buildResultJson("https://x?identifier=hr1", "pass", 1.0),
                StandardCharsets.UTF_8);

        new GenerateManifest(tempDir).run();

        JsonNode lang = MAPPER.readTree(tempDir.resolve("summary.json").toFile())
                              .path("languages").path("hr");
        int reportedPageCount = lang.path("pageCount").asInt();
        long actualFileCount  = Files.list(langDir.resolve("pages"))
                                     .filter(p -> p.getFileName().toString()
                                                   .matches("page-\\d{3}\\.json"))
                                     .count();
        assertEquals(reportedPageCount, actualFileCount,
                "pageCount in summary must equal the number of page files written");
    }

    // ── run: page file content ────────────────────────────────────────────────

    @Test
    void pageFileContainsExpectedFields(@TempDir Path tempDir)
            throws IOException {
        Path langDir = tempDir.resolve("guids_sl");
        Files.createDirectories(langDir);
        Files.writeString(langDir.resolve("r.json"),
                buildResultJson("https://x?identifier=sl1", "pass", 1.0),
                StandardCharsets.UTF_8);

        new GenerateManifest(tempDir).run();

        Path pageFile = langDir.resolve("pages").resolve("page-001.json");
        assertTrue(Files.exists(pageFile));

        JsonNode page = MAPPER.readTree(pageFile.toFile());
        assertTrue(page.isArray(), "Page file must be a JSON array");
        assertEquals(1, page.size(), "Page must contain exactly one record");

        JsonNode record = page.get(0);
        assertAll(
                () -> assertTrue(record.has("identifier"),
                        "Record must have 'identifier'"),
                () -> assertTrue(record.has("testedguid"),
                        "Record must have 'testedguid'"),
                () -> assertTrue(record.has("netScore"),
                        "Record must have 'netScore'"),
                () -> assertTrue(record.has("test_results"),
                        "Record must have 'test_results'")
        );
    }

    @Test
    void pageFileIdentifierExtractedFromUrl(@TempDir Path tempDir)
            throws IOException {
        Path langDir = tempDir.resolve("guids_el");
        Files.createDirectories(langDir);
        Files.writeString(langDir.resolve("r.json"),
                buildResultJson(
                        "https://example.org/oai?verb=GetRecord"
                        + "&metadataPrefix=oai_ddi25&identifier=my-hash-99",
                        "pass", 1.0),
                StandardCharsets.UTF_8);

        new GenerateManifest(tempDir).run();

        JsonNode page = MAPPER.readTree(
                langDir.resolve("pages").resolve("page-001.json").toFile());
        assertEquals("my-hash-99", page.get(0).path("identifier").asText(),
                "identifier must be extracted from the URL parameter");
    }

    // ── run: pagination boundary ──────────────────────────────────────────────

    @Test
    void runCreatesMultiplePagesWhenRecordsExceedPageSize(
            @TempDir Path tempDir) throws IOException {
        Path langDir = tempDir.resolve("guids_de");
        Files.createDirectories(langDir);

        // Write 201 result files to force two pages (PAGE_SIZE = 200)
        for (int i = 0; i < 201; i++) {
            Files.writeString(
                    langDir.resolve(String.format("r%04d.json", i)),
                    buildResultJson("https://x?identifier=id" + i, "pass", 1.0),
                    StandardCharsets.UTF_8);
        }

        new GenerateManifest(tempDir).run();

        long pageCount = Files.list(langDir.resolve("pages"))
                              .filter(p -> p.getFileName().toString()
                                            .matches("page-\\d{3}\\.json"))
                              .count();
        assertEquals(2, pageCount,
                "201 records must produce exactly 2 page files");
    }

    // ── summary.json structure ────────────────────────────────────────────────

    @Test
    void summaryContainsGeneratedTimestamp(@TempDir Path tempDir)
            throws IOException {
        new GenerateManifest(tempDir).run();
        JsonNode root = MAPPER.readTree(tempDir.resolve("summary.json").toFile());
        String generated = root.path("generated").asText("");
        assertFalse(generated.isBlank(), "'generated' must not be blank");
        // ISO-8601 instant contains a 'T'
        assertTrue(generated.contains("T"),
                "'generated' must be an ISO-8601 timestamp");
    }

    @Test
    void summaryOverallContainsAllFourFairCategories(@TempDir Path tempDir)
            throws IOException {
        new GenerateManifest(tempDir).run();
        JsonNode fair = MAPPER.readTree(tempDir.resolve("summary.json").toFile())
                              .path("overall").path("fair");
        assertAll(
                () -> assertTrue(fair.has("F"), "overall.fair must have F"),
                () -> assertTrue(fair.has("A"), "overall.fair must have A"),
                () -> assertTrue(fair.has("I"), "overall.fair must have I"),
                () -> assertTrue(fair.has("R"), "overall.fair must have R")
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a minimal Champion API result JSON with one test result.
     */
    private static String buildResultJson(
            String testedGuid, String result, double weight) {
        return "{"
                + "\"testedguid\": \"" + testedGuid + "\","
                + "\"test_results\": {"
                + "  \"F1-GUID\": {"
                + "    \"result\": \"" + result + "\","
                + "    \"weight\": " + weight
                + "  }"
                + "},"
                + "\"narratives\": [],"
                + "\"guidances\":  []"
                + "}";
    }

    /**
     * Builds a result JSON with test results for a specific FAIR test ID.
     */
    private static String buildResultJsonForFairTest(
            String testedGuid, String testId, String result) {
        return "{"
                + "\"testedguid\": \"" + testedGuid + "\","
                + "\"test_results\": {"
                + "  \"" + testId + "\": {"
                + "    \"result\": \"" + result + "\","
                + "    \"weight\": 1.0"
                + "  }"
                + "},"
                + "\"narratives\": [],"
                + "\"guidances\":  []"
                + "}";
    }

    /**
     * Builds a result JSON with one test result per supplied outcome string.
     * Test IDs are auto-generated as T0, T1, T2, … so they do not map to
     * any known FAIR category (intentional — keeps the helper generic).
     */
    private static String buildResultJsonMultiTest(
            String testedGuid, List<String> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"testedguid\": \"").append(testedGuid)
          .append("\", \"test_results\": {");
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"T").append(i).append("\": {")
              .append("\"result\": \"").append(results.get(i)).append("\",")
              .append("\"weight\": 1.0}");
        }
        sb.append("}, \"narratives\": [], \"guidances\": []}");
        return sb.toString();
    }
}