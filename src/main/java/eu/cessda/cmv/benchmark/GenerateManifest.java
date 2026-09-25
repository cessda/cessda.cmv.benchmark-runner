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

import eu.cessda.cmv.benchmark.models.*;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
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

    public static final int PAGE_SIZE = 200;

    private static final List<String> FAIR_CATEGORIES = List.of("F", "A", "I", "R");

    /**
     * Name of the per-set incremental cache file. Matched by the existing
     * {@code results/**&#47;*.json} .gitignore pattern, so it never needs
     * separate exclusion, and by the {@code *.json} glob used to list
     * result files below, so it must always be filtered out explicitly
     * there.
     */
    private static final String CACHE_FILENAME = ".manifest-cache.json";
    static final String IDENTIFIER_PARAMETER = "identifier=";

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

    // ── Entry point ──────────────────────────────────────────────────────────
    @SuppressWarnings("java:S106")
    public static void main(String[] args) throws IOException {
        String resultsDirArg = args.length > 0 ? args[0] : "results";
        Path resultsDir = Paths.get(resultsDirArg).toAbsolutePath().normalize();

        if (!Files.isDirectory(resultsDir)) {
            System.err.println("Results directory not found: " + resultsDir);
            System.exit(1);
        }

        LOG.log(Level.INFO, "Scanning {0} ...", resultsDir);
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

    // ── Main processing ──────────────────────────────────────────────────────

    /**
     * Extracts the bare identifier from an OAI-PMH GetRecord URL.
     * {@code https://…?verb=GetRecord&…&identifier=abc123} -> {@code abc123}
     */
    private static String extractIdentifier(URI url) {
        if (url == null) {
            return null;
        }

        // Attempt to extract the identifier from the query
        String query = url.getQuery();
        if (query != null) {
            int idx = query.lastIndexOf(IDENTIFIER_PARAMETER);
            if (idx != -1) {
                String after = query.substring(idx + IDENTIFIER_PARAMETER.length());
                int amp = after.indexOf('&');
                return amp != -1 ? after.substring(0, amp) : after;
            }
        }

        return url.toString();
    }

    public void run() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(resultsDir,
                entry -> entry.getFileName().toString().startsWith("guids_") && Files.isDirectory(entry))
        ) {
            for (Path entry : stream) {
                // Exclude "guids_" from the set name
                String set = entry.getFileName().toString().substring(6);
                processSet(set, entry);
            }
        }
        writeSummary();
        int totalRecords = setStats.values().stream().mapToInt(SetStats::getRecords).sum();
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

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(setDir, p -> {
            String fileName = p.getFileName().toString();
            return !fileName.startsWith("error_") // Filter out error files
                    && !fileName.equals(CACHE_FILENAME) // Filter out caches
                    && fileName.endsWith(".json")  // Select JSON files
                    && Files.isRegularFile(p); // Exclude directories
        })) {
            for (Path f : stream) {
                files.add(f);
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

        var currentPage = new ArrayList<SlimRecord>(PAGE_SIZE);

        int pageNumber = 1;
        int reused = 0;
        int reparsed = 0;

        for (Path file : files) {
            String name = file.getFileName().toString();

            long mtime = Files.getLastModifiedTime(file).toMillis();
            long size = Files.size(file);

            CachedRecord cached = cache.get(name);
            SlimRecord slim;

            if (cached != null && cached.mtime() == mtime && cached.size() == size) {
                slim = cached.slim();
                reused++;
            } else {
                try {
                    slim = buildSlimRecord(file);
                    reparsed++;
                } catch (JacksonException e) {
                    LOG.warning("Skipping unreadable file: " + file.getFileName() + " — " + e.getMessage());
                    continue;
                }
            }

            newCache.put(name, new CachedRecord(mtime, size, slim));
            stats.addTestResultMap(slim);

            currentPage.add(slim);
            if (currentPage.size() >= PAGE_SIZE) {
                writePage(pagesDir, pageNumber++, currentPage);
                currentPage.clear();
            }
        }

        if (!currentPage.isEmpty()) {
            writePage(pagesDir, pageNumber, currentPage);
        }

        saveCache(cacheFile, newCache);
        totalReused += reused;
        totalReparsed += reparsed;

        LOG.info(String.format(
                "  -> %d page(s) written (%d records; %d reused from cache, %d re-parsed)",
                pageNumber, stats.getRecords(), reused, reparsed));
    }

    // ── Incremental cache ───────────────────────────────────────────────────

    /**
     * Parses one result file from scratch and builds its slim page record.
     * result, whether freshly built here or retrieved from the cache.
     *
     * @param file the result file to parse
     * @return the slim record, or {@code null} if the file could not be
     *         read (already logged)
     */
    private SlimRecord buildSlimRecord(Path file) {
        JsonNode root = mapper.readTree(file);


        Map<String, SlimRecord.TestResults> testResultsMap = Collections.emptyMap();

        double netScore = 0.0;
        Set<String> passedNorm = new HashSet<>();

        JsonNode testResults = root.path("test_results");

        if (testResults.isObject()) {
            // Rewrite test_results with normalised test IDs.
            // Keep all tests so tenant-specific mappings (e.g. Oxford) can
            // still be categorised client-side via /api/config fair-map.
            testResultsMap = mapper.convertValue(testResults, new TypeReference<>() {
            });

            // Build a set of passed tests
            for (var entry : testResultsMap.entrySet()) {
                if ("pass".equals(entry.getValue().result())) {
                    passedNorm.add(entry.getKey());
                }
            }
        }

        int recMaturity = computeMaturity(passedNorm);

        URI testedGuid = mapper.convertValue(root.path("testedguid"), URI.class);
        String identifier = extractIdentifier(testedGuid);

        JsonNode narratives = root.path("narratives");
        JsonNode guidances = root.path("guidances");

        var narrativesList = mapper.convertValue(narratives, new TypeReference<List<String>>() {
        });
        var guidancesList = mapper.convertValue(guidances, new TypeReference<List<List<List<String>>>>() {
        });

        return new SlimRecord(
                identifier,
                testedGuid,
                netScore,
                recMaturity,
                testResultsMap,
                narrativesList,
                guidancesList
        );

    }

    /**
     * Loads the per-set cache, or returns an empty cache (forcing full
     * reprocessing of every file this run) if it is missing, unreadable,
     * or was built under a different FAIR / maturity configuration.
     */
    private Map<String, CachedRecord> loadCache(Path cacheFile) {
        if (!Files.isRegularFile(cacheFile)) {
            return Collections.emptyMap();
        }

        try {
            Cache cache = mapper.readValue(cacheFile.toFile(), Cache.class);

            String storedFingerprint = cache.configFingerprint();
            if (!storedFingerprint.equals(configFingerprint)) {
                LOG.info("  FAIR map / maturity configuration has changed since "
                        + "the cache was built — reprocessing all files this run.");
                return Collections.emptyMap();
            }

            return cache.files();
        } catch (JacksonException e) {
            LOG.warning("  Could not read manifest cache (" + cacheFile.getFileName()
                    + ") — reprocessing all files this run: " + e.getMessage());
            return Collections.emptyMap();
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
     * Writes the per-set cache. A failure here is never fatal to the run —
     * worst case, the next run just reprocesses everything again.
     */
    private void saveCache(Path cacheFile, Map<String, CachedRecord> cacheFileMap) {
        try {
            var cache = new Cache(configFingerprint, cacheFileMap);
            mapper.writeValue(cacheFile, cache);
        } catch (JacksonException e) {
            LOG.warning("  Could not write manifest cache (" + cacheFile.getFileName()
                    + "): " + e.getMessage());
        }
    }

    /**
     * @param pagesDir
     * @param pageNumber
     * @param records
     */
    // ── Output writers ───────────────────────────────────────────────────────
    private void writePage(Path pagesDir, int pageNumber, List<SlimRecord> records) {
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
    private void writeSummary() {

        int records = 0;
        int pass = 0;
        int fail = 0;
        int indet = 0;

        // maturity
        int none = 0;
        int level1 = 0;
        int level2 = 0;
        int level3 = 0;

        // faircat
        Map<String, List<SetStats.Fair>> fairList = new HashMap<>();

        // test
        Map<String, List<SetStats.Test>> testListMap = new HashMap<>();

        for (SetStats ls : setStats.values()) {
            records += ls.getRecords();
            pass += ls.getPass();
            fail += ls.getFail();
            indet += ls.getIndet();

            none += ls.getMatDist().getNone();
            level1 += ls.getMatDist().getLevel1();
            level2 += ls.getMatDist().getLevel2();
            level3 += ls.getMatDist().getLevel3();

            for (var fair : ls.getFair().entrySet()) {
                fairList.computeIfAbsent(fair.getKey(), k -> new ArrayList<>()).add(fair.getValue());
            }

            for (var test : ls.getTests().entrySet()) {
                testListMap.computeIfAbsent(test.getKey(), k -> new ArrayList<>()).add(test.getValue());
            }
        }

        var matDist = new SetStats.MatDist(none, level1, level2, level3);

        Map<String, SetStats.Fair> fair = new HashMap<>();
        for (var cat : fairList.entrySet()) {
            int passLocal = 0;
            int totalLocal = 0;
            for (var f : cat.getValue()) {
                passLocal += f.getPass();
                totalLocal += f.getTotal();
            }
            fair.put(cat.getKey(), new SetStats.Fair(passLocal, totalLocal));
        }

        Map<String, SetStats.Test> test = new HashMap<>();
        for (var testEntry : testListMap.entrySet()) {
            int passLocal = 0;
            int failLocal = 0;
            int indetLocal = 0;
            for (var t : testEntry.getValue()) {
                passLocal += t.getPass();
                failLocal += t.getFail();
                indetLocal += t.getIndet();
            }
            test.put(testEntry.getKey(), new SetStats.Test(passLocal, failLocal, indetLocal));
        }

        // Overall aggregation
        SetStats overall = new SetStats(fairMap, records, pass, fail, indet, fair, test, matDist);

        // Disable writing page count for top level stats
        overall.writePageCount(false);

        var summaryStats = new SummaryStats(Instant.now(), overall, setStats);

        Path out = resultsDir.resolve("summary.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(out, summaryStats);
        LOG.info("Wrote " + out);
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

}
