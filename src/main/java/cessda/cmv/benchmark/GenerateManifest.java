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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pre-processes FAIR benchmark results into two artefacts consumed by the
 * HTML dashboard:
 *
 * <ol>
 * <li>{@code results/summary.json} — fully aggregated statistics for every
 * language and overall totals. Loaded once by both {@code index.html}
 * and {@code language.html}; no individual record files are fetched by
 * the browser.</li>
 * <li>{@code results/guids_<lang>/pages/page-NNN.json} — slim, paginated
 * slices of the record list (200 records per page). Only the current
 * page is fetched when the user browses the records table.</li>
 * </ol>
 *
 * <p>
 * Each page file contains an array of compact record objects with the
 * fields the browser actually needs: {@code identifier}, {@code testedguid},
 * {@code test_results}, {@code narratives}, {@code guidances}, and a
 * pre-computed {@code netScore}.
 * </p>
 *
 * <h2>Usage</h2>
 * 
 * <pre>
 * java - cp &lt; classpath &gt; cessda.cmv.benchmark.GenerateManifest[resultsDir]
 * </pre>
 * <p>
 * If {@code resultsDir} is omitted it defaults to {@code ./results}.
 * </p>
 *
 * <h2>Expected input layout</h2>
 * 
 * <pre>
 *   results/
 *     guids_de/   <hash>.json ...
 *     guids_en/   <hash>.json ...
 * </pre>
 *
 * <h2>Output layout</h2>
 * 
 * <pre>
 *   results/
 *     summary.json
 *     guids_de/pages/page-001.json  page-002.json ...
 *     guids_en/pages/page-001.json  ...
 * </pre>
 */
public class GenerateManifest {

    // ── Constants ────────────────────────────────────────────────────────────

    private static final int PAGE_SIZE = 200;

    /**
     * Canonical test ID -> FAIR category. Keys are the normalised (uppercase,
     * underscore)
     * forms — the single source of truth for all 16 recognised tests.
     */
    private static final Map<String, String> FAIR_MAP = new LinkedHashMap<>();
    static {
        FAIR_MAP.put("F1_PID_ADHU", "F");
        FAIR_MAP.put("F1_GUID", "F");
        FAIR_MAP.put("F2A", "F");
        FAIR_MAP.put("F2B", "F");
        FAIR_MAP.put("F4", "F");
        FAIR_MAP.put("A1_1", "A");
       // FAIR_MAP.put("A1_2", "A");
        FAIR_MAP.put("I1_A", "I");
        FAIR_MAP.put("I2_A", "I");
        FAIR_MAP.put("R1_2_CPI", "R");
        FAIR_MAP.put("R1_3_CEK", "R");
        FAIR_MAP.put("R1_3_CTV", "R");
        FAIR_MAP.put("R1_3_DMOCV", "R");
        FAIR_MAP.put("R1_3_DAUV", "R");
        FAIR_MAP.put("R1_3_DTMV", "R");
        FAIR_MAP.put("R1_3_DSPV", "R");
    }

    /**
     * Normalise a test ID to the canonical form used in MATURITY_* sets:
     * trim, uppercase, replace hyphens and whitespace with underscores.
     * e.g. "F1-GUID" -> "F1_GUID", "R1-2-CPI " -> "R1_2_CPI"
     */
    private static String normTestId(String raw) {
        return raw.trim().toUpperCase()
                .replace('-', '_')
                .replaceAll("\\s+", "_");
    }

    /** Tests required for Maturity Level 1 (normalised IDs). */
    private static final java.util.Set<String> MATURITY_L1 = java.util.Set.of(
            "F1_GUID", "F2B", "F4", "A1_1"); // "A1_2");

    /** Tests required for Maturity Level 2 (superset of L1). */
    private static final java.util.Set<String> MATURITY_L2;
    static {
        var s = new java.util.HashSet<>(MATURITY_L1);
        s.addAll(java.util.List.of("F2A", "I1_A", "R1_2_CPI"));
        MATURITY_L2 = java.util.Collections.unmodifiableSet(s);
    }

    /** Tests required for Maturity Level 3 (superset of L2). */
    private static final java.util.Set<String> MATURITY_L3;
    static {
        var s = new java.util.HashSet<>(MATURITY_L2);
        s.addAll(java.util.List.of(
                "F1_PID_ADHU", "I2_A", "R1_3_CEK", "R1_3_CTV",
                "R1_3_DMOCV", "R1_3_DAUV", "R1_3_DTMV", "R1_3_DSPV"));
        MATURITY_L3 = java.util.Collections.unmodifiableSet(s);
    }

    /**
     * Compute the maturity level (0-3) for a set of normalised passing test IDs.
     */
    private static int computeMaturity(java.util.Set<String> passedNorm) {
        if (passedNorm.containsAll(MATURITY_L3))
            return 3;
        if (passedNorm.containsAll(MATURITY_L2))
            return 2;
        if (passedNorm.containsAll(MATURITY_L1))
            return 1;
        return 0;
    }

    private static final Logger LOG = Logger.getLogger(GenerateManifest.class.getName());

    /**
     * @param args
     * @throws IOException
     */
    // ── Entry point ──────────────────────────────────────────────────────────

    public static void main(String[] args) throws IOException {
        String resultsDirArg = args.length > 0 ? args[0] : "results";
        Path resultsDir = Paths.get(resultsDirArg).toAbsolutePath().normalize();

        if (!Files.isDirectory(resultsDir)) {
            LOG.severe("Results directory not found: " + resultsDir);
            System.exit(1);
        }

        LOG.info("Scanning " + resultsDir + " ...");
        new GenerateManifest(resultsDir).run();
    }

    // ── Fields ───────────────────────────────────────────────────────────────

    private final Path resultsDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, LangStats> langStats = new TreeMap<>();

    // ── Constructor ──────────────────────────────────────────────────────────

    public GenerateManifest(Path resultsDir) {
        this.resultsDir = resultsDir;
    }

    /**
     * @throws IOException
     */
    // ── Main processing ──────────────────────────────────────────────────────

    public void run() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(resultsDir)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry))
                    continue;
                String dirName = entry.getFileName().toString();
                if (!dirName.startsWith("guids_"))
                    continue;
                String lang = dirName.substring(6);
                processLanguage(lang, entry);
            }
        }
        writeSummary();
        int totalRecords = langStats.values().stream().mapToInt(s -> s.records).sum();
        LOG.info(String.format("Done. %d language(s), %d total records.", langStats.size(), totalRecords));
    }

    /**
     * @param lang
     * @param langDir
     * @throws IOException
     */
    // ── Per-language processing ──────────────────────────────────────────────

    private void processLanguage(String lang, Path langDir) throws IOException {
        LOG.info("Processing language: " + lang);

        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(langDir, "*.json")) {
            for (Path f : stream) {
                String name = f.getFileName().toString();
                if (!name.startsWith("error_")) {
                    files.add(f);
                }
            }
        }
        files.sort(null);

        if (files.isEmpty()) {
            LOG.warning("  No result files found in " + langDir);
            return;
        }
        LOG.info(String.format("  %d result file(s) found", files.size()));

        LangStats stats = new LangStats(lang);
        langStats.put(lang, stats);

        // Create (or clear) the pages directory
        Path pagesDir = langDir.resolve("pages");
        Files.createDirectories(pagesDir);
        try (DirectoryStream<Path> old = Files.newDirectoryStream(pagesDir, "page-*.json")) {
            for (Path p : old)
                Files.deleteIfExists(p);
        }

        List<ObjectNode> currentPage = new ArrayList<>(PAGE_SIZE);
        int pageNumber = 1;

        for (Path file : files) {
            JsonNode root;
            try {
                root = mapper.readTree(file.toFile());
            } catch (IOException e) {
                LOG.warning("  Skipping unreadable file: " + file.getFileName() + " — " + e.getMessage());
                continue;
            }

            // Aggregate stats
            JsonNode testResults = root.path("test_results");
            double netScore = 0.0;
            java.util.Set<String> passedNorm = new java.util.HashSet<>();
            if (testResults.isObject()) {
                @SuppressWarnings("deprecation")
                var fields = testResults.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    String testId = entry.getKey().trim();
                    JsonNode val = entry.getValue();
                    String result = val.path("result").asText("indeterminate");
                    netScore += val.path("weight").asDouble(0.0);
                    stats.addTestResult(testId, result);
                    if ("pass".equals(result)) {
                        passedNorm.add(normTestId(testId));
                    }
                }
            }
            ;
            stats.records++;

            // Per-record maturity level
            int recMaturity = computeMaturity(passedNorm);
            stats.maturityCounts[recMaturity]++;

            // Build slim page record
            ObjectNode slim = mapper.createObjectNode();
            String testedGuid = root.path("testedguid").asText("");
            String identifier = extractIdentifier(testedGuid);
            slim.put("identifier", identifier);
            slim.put("testedguid", testedGuid);
            slim.put("netScore", netScore);
            slim.put("maturity", recMaturity);
            // Rewrite test_results with normalised canonical IDs, dropping unknowns
            if (testResults.isObject()) {
                ObjectNode normResults = mapper.createObjectNode();
                @SuppressWarnings("deprecation")
                var slimFields = testResults.fields();
                while (slimFields.hasNext()) {
                    var e = slimFields.next();
                    String normId = normTestId(e.getKey());
                    if (FAIR_MAP.containsKey(normId)) {
                        normResults.set(normId, e.getValue());
                    }
                }
                slim.set("test_results", normResults);
            }
            JsonNode narratives = root.path("narratives");
            if (narratives.isArray())
                slim.set("narratives", narratives);
            JsonNode guidances = root.path("guidances");
            if (guidances.isArray())
                slim.set("guidances", guidances);

            currentPage.add(slim);
            if (currentPage.size() >= PAGE_SIZE) {
                writePage(pagesDir, pageNumber++, currentPage);
                currentPage.clear();
            }
        }

        if (!currentPage.isEmpty()) {
            writePage(pagesDir, pageNumber++, currentPage);
        }

        stats.pageCount = pageNumber - 1;
        LOG.info(String.format("  -> %d page(s) written (%d records)", stats.pageCount, stats.records));
    }

    /**
     * @param pagesDir
     * @param pageNumber
     * @param records
     * @throws IOException
     */
    // ── Output writers ───────────────────────────────────────────────────────

    private void writePage(Path pagesDir, int pageNumber, List<ObjectNode> records) throws IOException {
        Path out = pagesDir.resolve(String.format("page-%03d.json", pageNumber));
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), records);
    }

    /**
     * Writes {@code results/summary.json}.
     *
     * <pre>
     * {
     *   "generated": "2026-...",
     *   "overall": {
     *     "records": N, "pass": N, "fail": N, "indet": N,
     *     "fair": { "F": {"pass": N, "total": N}, ... },
     *     "tests": { "F1-GUID": {"pass": N, "fail": N, "indet": N}, ... }
     *   },
     *   "languages": {
     *     "de": { "records": N, "pageCount": N, "pass": N, "fail": N, "indet": N,
     *             "fair": {...}, "tests": {...} },
     *     ...
     *   }
     * }
     * </pre>
     */
    private void writeSummary() throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("generated", java.time.Instant.now().toString());

        // Overall aggregation
        LangStats overall = new LangStats("_overall");
        for (LangStats ls : langStats.values()) {
            overall.records += ls.records;
            overall.pass += ls.pass;
            overall.fail += ls.fail;
            overall.indet += ls.indet;
            for (int i = 0; i < 4; i++)
                overall.maturityCounts[i] += ls.maturityCounts[i];
            for (String cat : List.of("F", "A", "I", "R")) {
                overall.fair.get(cat)[0] += ls.fair.get(cat)[0];
                overall.fair.get(cat)[1] += ls.fair.get(cat)[1];
            }
            for (Map.Entry<String, int[]> e : ls.tests.entrySet()) {
                int[] dst = overall.tests.computeIfAbsent(e.getKey(), k -> new int[3]);
                int[] src = e.getValue();
                dst[0] += src[0];
                dst[1] += src[1];
                dst[2] += src[2];
            }
        }
        root.set("overall", statsToJson(overall, false));

        ObjectNode langsNode = mapper.createObjectNode();
        for (Map.Entry<String, LangStats> e : langStats.entrySet()) {
            ObjectNode ls = statsToJson(e.getValue(), true);
            langsNode.set(e.getKey(), ls);
        }
        root.set("languages", langsNode);

        Path out = resultsDir.resolve("summary.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), root);
        LOG.info("Wrote " + out);
    }

    /**
     * @param s
     * @param includePageCount
     * @return ObjectNode
     */
    // ── Helpers ──────────────────────────────────────────────────────────────

    private ObjectNode statsToJson(LangStats s, boolean includePageCount) {
        ObjectNode node = mapper.createObjectNode();
        node.put("records", s.records);
        if (includePageCount)
            node.put("pageCount", s.pageCount);
        node.put("pass", s.pass);
        node.put("fail", s.fail);
        node.put("indet", s.indet);

        // Maturity level for this set: derived from which tests have at least one pass
        int highestLevel = 0;
        if (s.maturityCounts[3] > 0)
            highestLevel = 3;
        else if (s.maturityCounts[2] > 0)
            highestLevel = 2;
        else if (s.maturityCounts[1] > 0)
            highestLevel = 1;
        node.put("maturityLevel", highestLevel);

        // Maturity distribution across records
        ObjectNode matDist = mapper.createObjectNode();
        matDist.put("none", s.maturityCounts[0]);
        matDist.put("level1", s.maturityCounts[1]);
        matDist.put("level2", s.maturityCounts[2]);
        matDist.put("level3", s.maturityCounts[3]);
        node.set("maturityDistribution", matDist);

        ObjectNode fairNode = mapper.createObjectNode();
        for (String cat : List.of("F", "A", "I", "R")) {
            int[] d = s.fair.get(cat);
            ObjectNode c = mapper.createObjectNode();
            c.put("pass", d[0]);
            c.put("total", d[1]);
            fairNode.set(cat, c);
        }
        node.set("fair", fairNode);

        ObjectNode testsNode = mapper.createObjectNode();
        for (Map.Entry<String, int[]> e : new TreeMap<>(s.tests).entrySet()) {
            int[] d = e.getValue();
            ObjectNode t = mapper.createObjectNode();
            t.put("pass", d[0]);
            t.put("fail", d[1]);
            t.put("indet", d[2]);
            testsNode.set(e.getKey(), t);
        }
        node.set("tests", testsNode);
        return node;
    }

    /**
     * Extracts the bare identifier from an OAI-PMH GetRecord URL.
     * {@code https://…?verb=GetRecord&…&identifier=abc123} -> {@code abc123}
     */
    private static String extractIdentifier(String url) {
        if (url == null || url.isBlank())
            return "";
        int idx = url.lastIndexOf("identifier=");
        if (idx < 0)
            return url;
        String after = url.substring(idx + "identifier=".length());
        int amp = after.indexOf('&');
        return amp >= 0 ? after.substring(0, amp) : after;
    }

    /**
     * @param testId
     * @return String
     */
    /**
     * Returns the FAIR category for a test ID, or {@code null} if the test is not
     * one of
     * the recognised 16. The ID is normalised before lookup so any variant spelling
     * (hyphens, spaces, mixed case) resolves to the same canonical entry.
     */
    static String fairCategory(String testId) {
        return FAIR_MAP.get(normTestId(testId));
    }

    // ── Inner class ──────────────────────────────────────────────────────────

    private static class LangStats {
        @SuppressWarnings("unused")
        private String set = null;
        int records = 0;
        int pass = 0;
        int fail = 0;
        int indet = 0;
        int pageCount = 0;

        /** FAIR category -> [passCount, totalCount] */
        final Map<String, int[]> fair = new LinkedHashMap<>();
        /** test ID -> [pass, fail, indet] */
        final Map<String, int[]> tests = new LinkedHashMap<>();

        /**
         * Count of records at each maturity level: index 0 = none, 1 = L1, 2 = L2, 3 =
         * L3.
         * For the set-level summary this is populated by processLanguage(); for
         * _overall it
         * is summed in writeSummary().
         */
        final int[] maturityCounts = new int[4];

        LangStats(String set) {
            this.set = set;
            for (String cat : List.of("F", "A", "I", "R"))
                fair.put(cat, new int[2]);
        }

        void addTestResult(String testId, String result) {
            // Normalise to canonical ID; skip tests not in the recognised 16
            String normId = normTestId(testId);
            if (!FAIR_MAP.containsKey(normId))
                return;
            int[] bucket = tests.computeIfAbsent(normId, k -> new int[3]);
            String cat = FAIR_MAP.get(normId);
            switch (result) {
                case "pass" -> {
                    pass++;
                    bucket[0]++;
                    if (cat != null) {
                        fair.get(cat)[0]++;
                        fair.get(cat)[1]++;
                    }
                }
                case "fail" -> {
                    fail++;
                    bucket[1]++;
                    if (cat != null)
                        fair.get(cat)[1]++;
                }
                default -> {
                    indet++;
                    bucket[2]++;
                    if (cat != null)
                        fair.get(cat)[1]++;
                }
            }
        }
    }
}