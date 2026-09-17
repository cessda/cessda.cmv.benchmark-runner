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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * Pre-processes FAIR benchmark results into two artefacts consumed by the
 * HTML dashboard:
 *
 * <ol>
 *   <li>{@code results/summary.json} — fully aggregated statistics for every
 *       set and overall totals. Loaded once by both {@code index.html}
 *       and {@code detail.html}; no individual record files are fetched by
 *       the browser.</li>
 *   <li>{@code results/guids_<set>/pages/page-NNN.json} — slim, paginated
 *       slices of the record list (200 records per page). Only the current
 *       page is fetched when the user browses the records table.</li>
 * </ol>
 *
 * <p>Each page file contains an array of compact record objects with the
 * fields the browser actually needs: {@code identifier}, {@code testedguid},
 * {@code test_results}, {@code narratives}, {@code guidances}, and a
 * pre-computed {@code netScore}.</p>
 *
 * <h2>Incremental caching</h2>
 *
 * <p>Every result file that has already been seen — unchanged in mtime and
 * size since the last run — is served from a small per-set cache
 * ({@code results/guids_<set>/.manifest-cache.json}) instead of being
 * re-read and re-parsed. Only new or re-run (overwritten) files are parsed
 * from scratch. This matters because sets grow into the tens of thousands
 * of records, and without caching every run re-parses every result file
 * ever produced, no matter how old.</p>
 *
 * <p>The cache stores each file's already-computed slim record, which
 * already carries the normalised test IDs, computed {@code netScore}, and
 * computed maturity level, so re-deriving that file's contribution to the
 * aggregate stats on a cache hit costs nothing more than iterating a small
 * in-memory object — no disk I/O beyond the one bulk read of the cache file
 * itself.</p>
 *
 * <p>The cache is keyed to the tenant's FAIR-category map and maturity-level
 * thresholds: if either has changed since the cache was written (an
 * operator edited {@code tenants.config}), the whole cache is discarded and
 * every file is reprocessed once, after which caching resumes as normal.
 * This is what stops a config change from being silently ignored for
 * records that happen not to have been re-run.</p>
 *
 * <p>A missing, corrupt, or unreadable cache is never a hard failure — it
 * just means this run reprocesses everything, exactly as before caching
 * existed.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -cp &lt;classpath&gt; cessda.cmv.benchmark.GenerateManifest [resultsDir]
 * </pre>
 * <p>If {@code resultsDir} is omitted it defaults to {@code ./results}.</p>
 *
 * <h2>Expected input layout</h2>
 * <pre>
 *   results/
 *     guids_de/   <hash>.json ...
 *     guids_en/   <hash>.json ...
 * </pre>
 *
 * <h2>Output layout</h2>
 * <pre>
 *   results/
 *     summary.json
 *     guids_de/pages/page-001.json  page-002.json ...
 *     guids_de/.manifest-cache.json  (internal — not read by the dashboard)
 *     guids_en/pages/page-001.json  ...
 * </pre>
 */
public class GenerateManifest {

    // ── Constants ────────────────────────────────────────────────────────────

    private static final int PAGE_SIZE = 200;

    private static final List<String> FAIR_CATEGORIES = List.of("F", "A", "I", "R");

    /**
     * Name of the per-set incremental cache file. Matched by the existing
     * {@code results/**&#47;*.json} .gitignore pattern, so it never needs
     * separate exclusion, and by the {@code *.json} glob used to list
     * result files below, so it must always be filtered out explicitly
     * there.
     */
    private static final String CACHE_FILENAME = ".manifest-cache.json";

    /**
     * Normalise a test ID to the canonical form used in tenant FAIR and
     * maturity configuration: trim, uppercase, replace hyphens and
     * whitespace with underscores.
     * e.g. "F1-GUID" -> "F1_GUID", "R1-2-CPI " -> "R1_2_CPI"
     */
    private static String normTestId(String raw) {
        return raw.trim().toUpperCase()
                  .replace('-', '_')
                  .replaceAll("\\s+", "_");
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
        new GenerateManifest(resultsDir, Map.of(), List.of(), List.of(), List.of()).run();
    }

    // ── Fields ───────────────────────────────────────────────────────────────

    private final Path resultsDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, SetStats> setStats = new TreeMap<>();
    private final Map<String, String> fairMap;
    private final Set<String> maturityLevel1Tests;
    private final Set<String> maturityLevel2Tests;
    private final Set<String> maturityLevel3Tests;

    /**
     * Fingerprint of {@link #fairMap} and the three maturity level sets,
     * used to invalidate the per-set cache whenever the tenant's FAIR /
     * maturity configuration changes between runs.
     */
    private final String configFingerprint;

    private int totalReused = 0;
    private int totalReparsed = 0;

    // ── Constructor ──────────────────────────────────────────────────────────

    public GenerateManifest(
            Path resultsDir,
            Map<String, String> fairMap,
            List<String> maturityLevel1,
            List<String> maturityLevel2,
            List<String> maturityLevel3) {
        this.resultsDir = resultsDir;
        this.fairMap = normaliseFairMap(fairMap);
        this.maturityLevel1Tests = normaliseTestIdSet(maturityLevel1);
        this.maturityLevel2Tests = normaliseTestIdSet(maturityLevel2);
        this.maturityLevel3Tests = normaliseTestIdSet(maturityLevel3);
        this.configFingerprint = computeConfigFingerprint(
                this.fairMap, this.maturityLevel1Tests,
                this.maturityLevel2Tests, this.maturityLevel3Tests);
    }

    /**
     * @throws IOException
     */
    // ── Main processing ──────────────────────────────────────────────────────

    public void run() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(resultsDir)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) continue;
                String dirName = entry.getFileName().toString();
                if (!dirName.startsWith("guids_")) continue;
                String set = dirName.substring(6);
                processSet(set, entry);
            }
        }
        writeSummary();
        int totalRecords = setStats.values().stream().mapToInt(s -> s.records).sum();
        LOG.info(String.format(
                "Done. %d set(s), %d total records (%d reused from cache, %d re-parsed).",
                setStats.size(), totalRecords, totalReused, totalReparsed));
    }

    /**
     * @param set
     * @param setDir
     * @throws IOException
     */
    // ── Per-set processing ──────────────────────────────────────────────

    private void processSet(String set, Path setDir) throws IOException {
        LOG.info("Processing set: " + set);

        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(setDir, "*.json")) {
            for (Path f : stream) {
                String name = f.getFileName().toString();
                if (!name.startsWith("error_") && !name.equals(CACHE_FILENAME)) {
                    files.add(f);
                }
            }
        }
        files.sort(null);

        if (files.isEmpty()) {
            LOG.warning("  No result files found in " + setDir);
            return;
        }
        LOG.info(String.format("  %d result file(s) found", files.size()));

        SetStats stats = new SetStats(fairMap);
        setStats.put(set, stats);

        Path cacheFile = setDir.resolve(CACHE_FILENAME);
        Map<String, CachedRecord> cache = loadCache(cacheFile);
        Map<String, CachedRecord> newCache = new LinkedHashMap<>();

        // Create (or clear) the pages directory
        Path pagesDir = setDir.resolve("pages");
        Files.createDirectories(pagesDir);
        try (DirectoryStream<Path> old = Files.newDirectoryStream(pagesDir, "page-*.json")) {
            for (Path p : old) Files.deleteIfExists(p);
        }

        List<ObjectNode> currentPage = new ArrayList<>(PAGE_SIZE);
        int pageNumber = 1;
        int reused = 0;
        int reparsed = 0;

        for (Path file : files) {
            String name = file.getFileName().toString();

            long mtime;
            long size;
            try {
                mtime = Files.getLastModifiedTime(file).toMillis();
                size = Files.size(file);
            } catch (IOException e) {
                LOG.warning("  Skipping unreadable file: " + name + " — " + e.getMessage());
                continue;
            }

            CachedRecord cached = cache.get(name);
            ObjectNode slim;

            if (cached != null && cached.mtime == mtime && cached.size == size) {
                slim = cached.slim;
                reused++;
            } else {
                slim = buildSlimRecord(file);
                if (slim == null) continue; // unreadable, already logged
                reparsed++;
            }

            newCache.put(name, new CachedRecord(mtime, size, slim));
            applyToStats(stats, slim);

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
        saveCache(cacheFile, newCache);
        totalReused += reused;
        totalReparsed += reparsed;

        LOG.info(String.format(
                "  -> %d page(s) written (%d records; %d reused from cache, %d re-parsed)",
                stats.pageCount, stats.records, reused, reparsed));
    }

    /**
     * Parses one result file from scratch and builds its slim page record.
     * Does not touch aggregate stats — call {@link #applyToStats} with the
     * result, whether freshly built here or retrieved from the cache.
     *
     * @param file the result file to parse
     * @return the slim record, or {@code null} if the file could not be
     *         read (already logged)
     */
    private ObjectNode buildSlimRecord(Path file) {
        JsonNode root;
        try {
            root = mapper.readTree(file);
        } catch (JacksonException e) {
            LOG.warning("  Skipping unreadable file: " + file.getFileName() + " — " + e.getMessage());
            return null;
        }

        JsonNode testResults = root.path("test_results");
        double netScore = 0.0;
        Set<String> passedNorm = new HashSet<>();
        ObjectNode normResults = mapper.createObjectNode();
        if (testResults.isObject()) {
            @SuppressWarnings("deprecation")
            var fields = testResults.fields();
            for (Map.Entry<String, JsonNode> entry : testResults.properties()) {
                String testId = entry.getKey().trim();
                JsonNode val = entry.getValue();
                String result = val.path("result").asText("indeterminate");
                netScore += val.path("weight").asDouble(0.0);
                if ("pass".equals(result)) {
                    passedNorm.add(normTestId(testId));
                }
                normResults.set(normTestId(testId), val);
            }
        }
        int recMaturity = computeMaturity(passedNorm);

        ObjectNode slim = mapper.createObjectNode();
        String testedGuid = root.path("testedguid").asText("");
        String identifier = extractIdentifier(testedGuid);
        slim.put("identifier", identifier);
        slim.put("testedguid", testedGuid);
        slim.put("netScore", netScore);
        slim.put("maturity", recMaturity);
        // Rewrite test_results with normalised test IDs.
        // Keep all tests so tenant-specific mappings (e.g. Oxford) can
        // still be categorised client-side via /api/config fair-map.
        if (testResults.isObject()) {
            slim.set("test_results", normResults);
        }
        JsonNode narratives = root.path("narratives");
        if (narratives.isArray()) slim.set("narratives", narratives);
        JsonNode guidances = root.path("guidances");
        if (guidances.isArray()) slim.set("guidances", guidances);
        return slim;
    }

    /**
     * Folds one record's already-computed slim data into the running set
     * stats. Deliberately driven off the slim record rather than the
     * original file, so a cache hit and a fresh parse update stats
     * identically — the slim record already carries normalised test IDs
     * and the computed maturity level.
     */
    private void applyToStats(SetStats stats, ObjectNode slim) {
        stats.records++;
        int recMaturity = slim.path("maturity").asInt(0);
        stats.maturityCounts[recMaturity]++;

        JsonNode testResults = slim.path("test_results");
        if (testResults.isObject()) {
            @SuppressWarnings("deprecation")
            var fields = testResults.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String testId = entry.getKey(); // already normalised
                String result = entry.getValue().path("result").asText("indeterminate");
                stats.addTestResult(testId, result);
            }
        }
    }

    // ── Incremental cache ───────────────────────────────────────────────────

    private record CachedRecord(long mtime, long size, ObjectNode slim) {}

    /**
     * Loads the per-set cache, or returns an empty cache (forcing full
     * reprocessing of every file this run) if it is missing, unreadable,
     * or was built under a different FAIR / maturity configuration.
     */
    private Map<String, CachedRecord> loadCache(Path cacheFile) {
        Map<String, CachedRecord> result = new LinkedHashMap<>();
        if (!Files.isRegularFile(cacheFile)) {
            return result;
        }
        try {
            JsonNode root = mapper.readTree(cacheFile.toFile());
            String storedFingerprint = root.path("configFingerprint").asText("");
            if (!storedFingerprint.equals(configFingerprint)) {
                LOG.info("  FAIR map / maturity configuration has changed since "
                        + "the cache was built — reprocessing all files this run.");
                return result;
            }
            JsonNode filesNode = root.path("files");
            if (filesNode.isObject()) {
                @SuppressWarnings("deprecation")
                var fields = filesNode.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    JsonNode v = entry.getValue();
                    JsonNode slimNode = v.get("slim");
                    if (!(slimNode instanceof ObjectNode slim)) continue;
                    result.put(entry.getKey(), new CachedRecord(
                            v.path("mtime").asLong(-1),
                            v.path("size").asLong(-1),
                            slim));
                }
            }
        } catch (IOException e) {
            LOG.warning("  Could not read manifest cache (" + cacheFile.getFileName()
                    + ") — reprocessing all files this run: " + e.getMessage());
            result.clear();
        }
        return result;
    }

    /**
     * Writes the per-set cache. A failure here is never fatal to the run —
     * worst case, the next run just reprocesses everything again.
     */
    private void saveCache(Path cacheFile, Map<String, CachedRecord> cache) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("configFingerprint", configFingerprint);
            ObjectNode filesNode = mapper.createObjectNode();
            for (Map.Entry<String, CachedRecord> e : cache.entrySet()) {
                ObjectNode entryNode = mapper.createObjectNode();
                entryNode.put("mtime", e.getValue().mtime());
                entryNode.put("size", e.getValue().size());
                entryNode.set("slim", e.getValue().slim());
                filesNode.set(e.getKey(), entryNode);
            }
            root.set("files", filesNode);
            // Compact, not pretty-printed: this is an internal cache, never
            // read by the dashboard or a human.
            mapper.writeValue(cacheFile.toFile(), root);
        } catch (IOException e) {
            LOG.warning("  Could not write manifest cache (" + cacheFile.getFileName()
                    + "): " + e.getMessage());
        }
    }

    /**
     * Builds a stable fingerprint of the FAIR map and maturity level sets,
     * so a change to either invalidates every set's cache on the next run.
     * Built from a canonical (sorted) string form so the same configuration
     * always yields the same fingerprint regardless of map/set iteration
     * order.
     */
    private static String computeConfigFingerprint(
            Map<String, String> fairMap,
            Set<String> level1, Set<String> level2, Set<String> level3) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(fairMap).forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
        sb.append('|');
        new TreeSet<>(level1).forEach(t -> sb.append(t).append(','));
        sb.append('|');
        new TreeSet<>(level2).forEach(t -> sb.append(t).append(','));
        sb.append('|');
        new TreeSet<>(level3).forEach(t -> sb.append(t).append(','));
        return Integer.toHexString(sb.toString().hashCode());
    }

    /**
     * @param pagesDir
     * @param pageNumber
     * @param records
     * @throws IOException
     */
    // ── Output writers ───────────────────────────────────────────────────────
    private void writePage(Path pagesDir, int pageNumber, List<ObjectNode> records) {
        Path out = pagesDir.resolve(String.format("page-%03d.json", pageNumber));
        mapper.writerWithDefaultPrettyPrinter().writeValue(out, records);
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
     *   "sets": {
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
        SetStats overall = new SetStats(fairMap);
        for (SetStats ls : setStats.values()) {
            overall.records += ls.records;
            overall.pass    += ls.pass;
            overall.fail    += ls.fail;
            overall.indet   += ls.indet;
            for (int i = 0; i < 4; i++) overall.maturityCounts[i] += ls.maturityCounts[i];
            for (String cat : FAIR_CATEGORIES) {
                overall.fair.get(cat)[0] += ls.fair.get(cat)[0];
                overall.fair.get(cat)[1] += ls.fair.get(cat)[1];
            }
            for (Map.Entry<String, int[]> e : ls.tests.entrySet()) {
                int[] dst = overall.tests.computeIfAbsent(e.getKey(), k -> new int[3]);
                int[] src = e.getValue();
                dst[0] += src[0]; dst[1] += src[1]; dst[2] += src[2];
            }
        }
        root.set("overall", statsToJson(overall, false));

        ObjectNode setsNode = mapper.createObjectNode();
        for (Map.Entry<String, SetStats> e : setStats.entrySet()) {
            ObjectNode ls = statsToJson(e.getValue(), true);
            setsNode.set(e.getKey(), ls);
        }
        root.set("sets", setsNode);

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

    private ObjectNode statsToJson(SetStats s, boolean includePageCount) {
        ObjectNode node = mapper.createObjectNode();
        node.put("records", s.records);
        if (includePageCount) node.put("pageCount", s.pageCount);
        node.put("pass",    s.pass);
        node.put("fail",    s.fail);
        node.put("indet",   s.indet);

        // Maturity level for this set: the highest level achieved by at
        // least one record, derived from the per-record maturityCounts
        // already accumulated in processSet().
        //
        // The previous approach (checking which tests have at least one
        // pass across the whole set) incorrectly returned L0 whenever a
        // required test never passed for any record — even when many
        // records individually met a higher level through a different
        // combination of passing tests.
        int setMaturity = 0;
        for (int i = 3; i >= 1; i--) {
            if (s.maturityCounts[i] > 0) { setMaturity = i; break; }
        }
        node.put("maturityLevel", setMaturity);

        // Maturity distribution across records
        ObjectNode matDist = mapper.createObjectNode();
        matDist.put("none",    s.maturityCounts[0]);
        matDist.put("level1",  s.maturityCounts[1]);
        matDist.put("level2",  s.maturityCounts[2]);
        matDist.put("level3",  s.maturityCounts[3]);
        node.set("maturityDistribution", matDist);

        ObjectNode fairNode = mapper.createObjectNode();
        for (String cat : FAIR_CATEGORIES) {
            int[] d = s.fair.get(cat);
            ObjectNode c = mapper.createObjectNode();
            c.put("pass",  d[0]);
            c.put("total", d[1]);
            fairNode.set(cat, c);
        }
        node.set("fair", fairNode);

        ObjectNode testsNode = mapper.createObjectNode();
        for (Map.Entry<String, int[]> e : new TreeMap<>(s.tests).entrySet()) {
            int[] d = e.getValue();
            ObjectNode t = mapper.createObjectNode();
            t.put("pass",  d[0]);
            t.put("fail",  d[1]);
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
        if (url == null || url.isBlank()) return "";
        int idx = url.lastIndexOf("identifier=");
        if (idx < 0) return url;
        String after = url.substring(idx + "identifier=".length());
        int amp = after.indexOf('&');
        return amp >= 0 ? after.substring(0, amp) : after;
    }

    private int computeMaturity(Set<String> passedNorm) {
        if (!maturityLevel3Tests.isEmpty()
                && passedNorm.containsAll(maturityLevel3Tests)) return 3;
        if (!maturityLevel2Tests.isEmpty()
                && passedNorm.containsAll(maturityLevel2Tests)) return 2;
        if (!maturityLevel1Tests.isEmpty()
                && passedNorm.containsAll(maturityLevel1Tests)) return 1;
        return 0;
    }

    private static Map<String, String> normaliseFairMap(Map<String, String> fairMap) {
        Map<String, String> normalised = new LinkedHashMap<>();
        if (fairMap == null) return normalised;
        for (Map.Entry<String, String> e : fairMap.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (key == null || value == null) continue;
            String cat = value.trim().toUpperCase();
            if (!FAIR_CATEGORIES.contains(cat)) continue;
            normalised.put(normTestId(key), cat);
        }
        return normalised;
    }

    private static Set<String> normaliseTestIdSet(List<String> rawTests) {
        Set<String> out = new HashSet<>();
        if (rawTests == null) return out;
        for (String test : rawTests) {
            if (test == null || test.isBlank()) continue;
            out.add(normTestId(test));
        }
        return out;
    }

    // ── Inner class ──────────────────────────────────────────────────────────

    private class SetStats {
        private final Map<String, String> fairMap;
        int records   = 0;
        int pass      = 0;
        int fail      = 0;
        int indet     = 0;
        int pageCount = 0;

        /** FAIR category -> [passCount, totalCount] */
        final Map<String, int[]> fair  = new LinkedHashMap<>();
        /** test ID -> [pass, fail, indet] */
        final Map<String, int[]> tests = new LinkedHashMap<>();

        /**
         * Count of records at each maturity level: index 0 = none, 1 = L1, 2 = L2, 3 = L3.
         * For the set-level summary this is populated by processSet(); for _overall it
         * is summed in writeSummary().
         */
        final int[] maturityCounts = new int[4];

        SetStats(Map<String, String> fairMap) {
            this.fairMap = fairMap;
            for (String cat : FAIR_CATEGORIES) fair.put(cat, new int[2]);
        }

        void addTestResult(String testId, String result) {
            String normId = normTestId(testId);

            switch (result) {
                case "pass" -> pass++;
                case "fail" -> fail++;
                default -> indet++;
            }

            String cat = fairMap.get(normId);
            if (cat == null) return;
            int[] bucket = tests.computeIfAbsent(normId, k -> new int[3]);
            switch (result) {
                case "pass" -> {
                    bucket[0]++;
                    fair.get(cat)[0]++;
                    fair.get(cat)[1]++;
                }
                case "fail" -> {
                    bucket[1]++;
                    fair.get(cat)[1]++;
                }
                default -> {
                    bucket[2]++;
                    fair.get(cat)[1]++;
                }
            }
        }
    }
}
