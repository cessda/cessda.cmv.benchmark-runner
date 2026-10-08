package eu.cessda.cmv.benchmark.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static eu.cessda.cmv.benchmark.GenerateManifest.PAGE_SIZE;
import static java.lang.Math.max;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SetStats {
    private final Map<String, String> fairMap;
    /**
     * FAIR category -> [passCount, totalCount]
     */
    private final Map<String, Fair> fair;
    /**
     * test ID -> [pass, fail, indet]
     */
    private final Map<String, Test> tests;
    /**
     * Count of records at each maturity level: index 0 = none, 1 = L1, 2 = L2, 3 = L3.
     * For the set-level summary this is populated by processSet(); for _overall it
     * is summed in writeSummary().
     */
    private final MatDist matDist;
    private int records;
    private int pass;
    private int fail;
    private int indet;

    private boolean writePageCount = true;

    public SetStats(Map<String, String> fairMap) {
        this.fairMap = fairMap;
        this.records = 0;
        this.pass = 0;
        this.fail = 0;
        this.indet = 0;
        this.fair = new HashMap<>();
        this.tests = new HashMap<>();
        this.matDist = new MatDist();
    }

    @JsonCreator
    public SetStats(
            Map<String, String> fairMap,
            int records,
            int pass,
            int fail,
            int indet,
            Map<String, Fair> fair,
            Map<String, Test> tests,
            MatDist matDist
    ) {
        this.fairMap = fairMap;
        this.records = records;
        this.pass = pass;
        this.fail = fail;
        this.indet = indet;
        this.fair = fair;
        this.tests = tests;
        this.matDist = matDist;
    }

    /**
     * Folds one record's already-computed slim data into the running set
     * stats. Deliberately driven off the slim record rather than the
     * original file, so a cache hit and a fresh parse update stats
     * identically — the slim record already carries normalised test IDs
     * and the computed maturity level.
     */
    public void addTestResultMap(SlimRecord slim) {
        records++;

        int recMaturity = slim.maturity();
        switch (recMaturity) {
            case 0 -> matDist.none++;
            case 1 -> matDist.level1++;
            case 2 -> matDist.level2++;
            case 3 -> matDist.level3++;
        }

        Map<String, SlimRecord.TestResults> testResults = slim.testResults();
        for (Map.Entry<String, SlimRecord.TestResults> entry : testResults.entrySet()) {
            String testId = entry.getKey(); // already normalised
            String result = entry.getValue().result();
            if (result == null) {
                result = "indeterminate";
            }
            addTestResult(testId, result);
        }
    }

    public void addTestResult(String testId, String result) {
        switch (result) {
            case "pass" -> pass++;
            case "fail" -> fail++;
            default -> indet++;
        }

        Test bucket = tests.computeIfAbsent(testId, k -> new Test());
        switch (result) {
            case "pass" -> bucket.pass++;
            case "fail" -> bucket.fail++;
            default -> bucket.indet++;
        }

        String cat = fairMap.get(testId);
        if (cat != null) {
            Fair fairCategory = fair.computeIfAbsent(cat, k -> new Fair());
            fairCategory.total++;
            if (result.equals("pass")) {
                fairCategory.pass++;
            }
        }
    }

    public Map<String, String> getFairMap() {
        return fairMap;
    }

    public int getRecords() {
        return records;
    }

    public int getPass() {
        return pass;
    }

    public int getFail() {
        return fail;
    }

    public int getIndet() {
        return indet;
    }

    public Integer getPageCount() {
        if (writePageCount) {
            return (max(records - 1, 0) / PAGE_SIZE) + 1;
        } else {
            return null;
        }
    }

    public Map<String, Fair> getFair() {
        return fair;
    }

    public Map<String, Test> getTests() {
        return tests;
    }

    public MatDist getMatDist() {
        return matDist;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SetStats setStats)) return false;
        return records == setStats.records && pass == setStats.pass && fail == setStats.fail && indet == setStats.indet && Objects.equals(fairMap, setStats.fairMap) && Objects.equals(fair, setStats.fair) && Objects.equals(tests, setStats.tests) && Objects.equals(matDist, setStats.matDist);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fairMap, records, pass, fail, indet, fair, tests, matDist);
    }

    @JsonIgnore
    public void writePageCount(boolean writePageCount) {
        this.writePageCount = writePageCount;
    }

    public static final class MatDist {
        private int none;
        private int level1;
        private int level2;
        private int level3;

        private MatDist() {
            this.none = 0;
            this.level1 = 0;
            this.level2 = 0;
            this.level3 = 0;
        }

        @JsonCreator
        public MatDist(int none, int level1, int level2, int level3) {
            this.none = none;
            this.level1 = level1;
            this.level2 = level2;
            this.level3 = level3;
        }

        public int getNone() {
            return none;
        }

        public int getLevel1() {
            return level1;
        }

        public int getLevel2() {
            return level2;
        }

        public int getLevel3() {
            return level3;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj == null || obj.getClass() != this.getClass()) return false;
            var that = (MatDist) obj;
            return this.none == that.none &&
                    this.level1 == that.level1 &&
                    this.level2 == that.level2 &&
                    this.level3 == that.level3;
        }

        @Override
        public int hashCode() {
            return Objects.hash(none, level1, level2, level3);
        }

        @Override
        public String toString() {
            return "MatDist[" +
                    "none=" + none + ", " +
                    "level1=" + level1 + ", " +
                    "level2=" + level2 + ", " +
                    "level3=" + level3 + ']';
        }
    }

    public static final class Fair {
        private int pass;
        private int total;

        private Fair() {
            this.pass = 0;
            this.total = 0;
        }

        @JsonCreator
        public Fair(int pass, int total) {
            this.pass = pass;
            this.total = total;
        }

        public int getPass() {
            return pass;
        }

        public int getTotal() {
            return total;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj == null || obj.getClass() != this.getClass()) return false;
            var that = (Fair) obj;
            return this.pass == that.pass &&
                    this.total == that.total;
        }

        @Override
        public int hashCode() {
            return Objects.hash(pass, total);
        }

        @Override
        public String toString() {
            return "Fair[" +
                    "pass=" + pass + ", " +
                    "total=" + total + ']';
        }
    }

    public static final class Test {
        private int pass;
        private int fail;
        private int indet;

        private Test() {
            this.pass = 0;
            this.fail = 0;
            this.indet = 0;
        }

        @JsonCreator
        public Test(int pass, int fail, int indet) {
            this.pass = pass;
            this.fail = fail;
            this.indet = indet;
        }

        public int getPass() {
            return pass;
        }

        public int getFail() {
            return fail;
        }

        public int getIndet() {
            return indet;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj == null || obj.getClass() != this.getClass()) return false;
            var that = (Test) obj;
            return this.pass == that.pass &&
                    this.fail == that.fail &&
                    this.indet == that.indet;
        }

        @Override
        public int hashCode() {
            return Objects.hash(pass, fail, indet);
        }

        @Override
        public String toString() {
            return "Test[" +
                    "pass=" + pass + ", " +
                    "fail=" + fail + ", " +
                    "indet=" + indet + ']';
        }
    }
}
