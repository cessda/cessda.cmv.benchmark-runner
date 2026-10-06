/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package eu.cessda.cmv.benchmark.tenant;

import eu.cessda.cmv.benchmark.MaturityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MaturityConfig}, in
 * particular that a tenant config which never sets {@code method} — as every
 * {@code tenants.config} entry did before weighted-score support was added —
 * keeps behaving exactly as CESSDA's existing {@code application.yaml}
 * expects, with no changes required on its part.
 */
class TenantPropertiesTest {

    @Test
    @DisplayName("method defaults to CHECKLIST when never set (relaxed-binding omission)")
    void methodDefaultsToChecklist() {
        MaturityConfig levels = new MaturityConfig();
        assertEquals(MaturityConfig.Method.CHECKLIST, levels.getMethod());
    }

    @Test
    @DisplayName("setMethod(null) falls back to CHECKLIST rather than storing null")
    void setMethodNullFallsBackToChecklist() {
        MaturityConfig levels = new MaturityConfig();
        levels.setMethod(MaturityConfig.Method.WEIGHTED_SCORE);
        levels.setMethod(null);
        assertEquals(MaturityConfig.Method.CHECKLIST, levels.getMethod());
    }

    @Test
    @DisplayName("A checklist-only config (CESSDA's shape) leaves the weighted-score fields empty/null")
    void checklistOnlyConfigLeavesWeightedScoreFieldsUnset() {
        MaturityConfig levels = new MaturityConfig();
        levels.setLevel1(List.of("F1_GUID", "F2B"));
        levels.setLevel2(List.of("F1_GUID", "F2B", "A1_1"));
        levels.setLevel3(List.of("F1_GUID", "F2B", "A1_1", "I1_A"));

        assertEquals(MaturityConfig.Method.CHECKLIST, levels.getMethod());
        assertTrue(levels.getCategoryMax().isEmpty(),
                "categoryMax must stay empty for a tenant that never configures it");
        assertNull(levels.getLevel1Threshold());
        assertNull(levels.getLevel2Threshold());
        assertNull(levels.getLevel3Threshold());
    }

    @Test
    @DisplayName("Null lists/maps passed to setters fall back to empty rather than null")
    void nullSettersFallBackToEmpty() {
        MaturityConfig levels = new MaturityConfig();
        levels.setLevel1(null);
        levels.setLevel2(null);
        levels.setLevel3(null);
        levels.setCategoryMax(null);

        assertNotNull(levels.getLevel1());
        assertTrue(levels.getLevel1().isEmpty());
        assertNotNull(levels.getLevel2());
        assertTrue(levels.getLevel2().isEmpty());
        assertNotNull(levels.getLevel3());
        assertTrue(levels.getLevel3().isEmpty());
        assertNotNull(levels.getCategoryMax());
        assertTrue(levels.getCategoryMax().isEmpty());
    }

    @Test
    @DisplayName("A weighted-score config (Oxford's shape) round-trips method, categoryMax and thresholds")
    void weightedScoreConfigRoundTrips() {
        MaturityConfig levels = new MaturityConfig();
        levels.setMethod(MaturityConfig.Method.WEIGHTED_SCORE);
        levels.setCategoryMax(java.util.Map.of("F", 32.0, "A", 14.0, "I", 13.0, "R", 36.0));
        levels.setLevel1Threshold(25.0);
        levels.setLevel2Threshold(50.0);

        assertEquals(MaturityConfig.Method.WEIGHTED_SCORE, levels.getMethod());
        assertEquals(32.0, levels.getCategoryMax().get("F"));
        assertEquals(25.0, levels.getLevel1Threshold());
        assertEquals(50.0, levels.getLevel2Threshold());
        assertNull(levels.getLevel3Threshold(), "level3Threshold has not been supplied yet");
    }
}
