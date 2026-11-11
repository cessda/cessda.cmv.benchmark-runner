package eu.cessda.cmv.benchmark;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a record's maturity level (0-3) is derived from its test
 * results. Two mutually exclusive models are supported, selected
 * by {@link #method}:
 *
 * <ul>
 *   <li>{@link Method#CHECKLIST} (default) — a record reaches a
 *       level only if it passed <em>every</em> test named in that
 *       level's list ({@link #level1}/{@link #level2}/
 *       {@link #level3}). Lists are cumulative by convention
 *       (level2 should list a superset of level1's tests, etc.),
 *       though this is not enforced. This is CESSDA's model and
 *       the only one that existed before weighted-score support
 *       was added — existing {@code tenants.config} entries that
 *       set only {@code level1}/{@code level2}/{@code level3}
 *       need no changes at all.</li>
 *   <li>{@link Method#WEIGHTED_SCORE} — each FAIR category (F, A,
 *       I, R) contributes a percentage, {@code (sum of that
 *       category's earned test weights) / categoryMax(category)
 *       * 100}; a record's overall score is the average of the
 *       categories present in {@link #categoryMax}. A level is
 *       reached once that score meets its threshold
 *       ({@link #level1Threshold}/{@link #level2Threshold}/
 *       {@link #level3Threshold}). This is Oxford's model,
 *       matching the weighted percentage formulas in their
 *       Benchmark Assessment Algorithm spreadsheet.</li>
 * </ul>
 */
public class MaturityConfig {

    private Method method = Method.CHECKLIST;
    // ── CHECKLIST fields ────────────────────────────────────────
    private List<String> level1 = List.of();
    private List<String> level2 = List.of();
    private List<String> level3 = List.of();
    /**
     * FAIR category letter (F/A/I/R) -> that category's maximum achievable weight.
     */
    private Map<String, Double> categoryMax = new LinkedHashMap<>();
    private Double level1Threshold;
    private Double level2Threshold;

    // ── WEIGHTED_SCORE fields ───────────────────────────────────
    private Double level3Threshold;

    public MaturityConfig() {
    }

    public MaturityConfig(Method method, List<String> level1, List<String> level2, List<String> level3, Map<String, Double> categoryMax, Double level1Threshold, Double level2Threshold, Double level3Threshold) {
        this.method = method;
        this.level1 = level1;
        this.level2 = level2;
        this.level3 = level3;
        this.categoryMax = categoryMax;
        this.level1Threshold = level1Threshold;
        this.level2Threshold = level2Threshold;
        this.level3Threshold = level3Threshold;
    }

    public static MaturityConfig checklist(
            List<String> level1, List<String> level2, List<String> level3) {
        return new MaturityConfig(
                MaturityConfig.Method.CHECKLIST, level1, level2, level3, Map.of(), null, null, null);
    }

    public static MaturityConfig weightedScore(
            Map<String, Double> categoryMax,
            Double level1Threshold, Double level2Threshold, Double level3Threshold) {
        return new MaturityConfig(
                MaturityConfig.Method.WEIGHTED_SCORE, List.of(), List.of(), List.of(),
                categoryMax, level1Threshold, level2Threshold, level3Threshold);
    }

    public Method getMethod() {
        return method;
    }

    public void setMethod(Method method) {
        this.method = method != null ? method : Method.CHECKLIST;
    }

    public List<String> getLevel1() {
        return level1;
    }

    public void setLevel1(List<String> level1) {
        this.level1 = level1 != null ? level1 : List.of();
    }

    public List<String> getLevel2() {
        return level2;
    }

    public void setLevel2(List<String> level2) {
        this.level2 = level2 != null ? level2 : List.of();
    }

    public List<String> getLevel3() {
        return level3;
    }

    public void setLevel3(List<String> level3) {
        this.level3 = level3 != null ? level3 : List.of();
    }

    public Map<String, Double> getCategoryMax() {
        return categoryMax;
    }

    public void setCategoryMax(Map<String, Double> categoryMax) {
        this.categoryMax = categoryMax != null ? categoryMax : new LinkedHashMap<>();
    }

    public Double getLevel1Threshold() {
        return level1Threshold;
    }

    public void setLevel1Threshold(Double level1Threshold) {
        this.level1Threshold = level1Threshold;
    }

    public Double getLevel2Threshold() {
        return level2Threshold;
    }

    public void setLevel2Threshold(Double level2Threshold) {
        this.level2Threshold = level2Threshold;
    }

    public Double getLevel3Threshold() {
        return level3Threshold;
    }

    public void setLevel3Threshold(Double level3Threshold) {
        this.level3Threshold = level3Threshold;
    }

    public enum Method {CHECKLIST, WEIGHTED_SCORE}


}
