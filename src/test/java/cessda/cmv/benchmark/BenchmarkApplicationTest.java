/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package cessda.cmv.benchmark;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Smoke test that verifies the Spring application context loads
 * without errors.
 *
 * <p>The {@code test} property overrides point both volume paths at
 * the OS temporary directory so the test does not depend on
 * {@code /data} or {@code /results} existing on the host machine.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
    "benchmark.data-dir=${java.io.tmpdir}/benchmark-test-data",
    "benchmark.results-dir=${java.io.tmpdir}/benchmark-test-results"
})
class BenchmarkApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Spring application context loads successfully")
    void contextLoads() {
        assertNotNull(context,
            "Application context must not be null");
    }

    @Test
    @DisplayName("All expected beans are present in the context")
    void expectedBeansAreRegistered() {
        assertNotNull(
            context.getBean(
                "benchmarkController",
                cessda.cmv.benchmark.controller.BenchmarkController.class),
            "BenchmarkController bean must be registered");

        assertNotNull(
            context.getBean(
                "benchmarkService",
                cessda.cmv.benchmark.service.BenchmarkService.class),
            "BenchmarkService bean must be registered");

        assertNotNull(
            context.getBean(
                "webConfig",
                cessda.cmv.benchmark.config.WebConfig.class),
            "WebConfig bean must be registered");
    }
}