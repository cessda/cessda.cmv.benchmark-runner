/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package cessda.cmv.benchmark.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cessda.cmv.benchmark.controller.BenchmarkController;
import cessda.cmv.benchmark.service.BenchmarkService;

/**
 * Tests for {@link WebConfig}.
 *
 * <p>Verifies that the {@code /results/**} URL prefix is correctly mapped
 * to the configured results directory on disk, so that the HTML dashboard
 * can fetch {@code results/summary.json} and page files via relative
 * URLs.</p>
 *
 * <p>A {@code @TempDir} is used to create an isolated directory on disk.
 * Because {@code @WebMvcTest} does not support {@code @TempDir} injection
 * directly into the test class alongside context configuration, the temp
 * directory path is provided via a fixed {@code TestPropertySource} value.
 * The actual directory is created in a {@code static} initialiser so it
 * exists before the Spring context starts.</p>
 */
@WebMvcTest(controllers = BenchmarkController.class)
@TestPropertySource(properties = {
    "benchmark.results-dir=${java.io.tmpdir}/webconfig-test-results",
    "benchmark.data-dir=${java.io.tmpdir}/webconfig-test-data"
})
class WebConfigTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BenchmarkService service;

    @Test
    @DisplayName("GET /results/summary.json returns 200 when file exists")
    void summaryJsonIsServedUnderResultsPrefix(@TempDir Path tempRoot)
            throws Exception {
        // Resolve the same path that TestPropertySource points at.
        Path resultsDir = Path.of(
            System.getProperty("java.io.tmpdir"), "webconfig-test-results");
        Files.createDirectories(resultsDir);

        String summaryContent =
            "{\"generated\":\"2026-01-01T00:00:00Z\","
            + "\"overall\":{},\"languages\":{}}";
        Files.writeString(
            resultsDir.resolve("summary.json"),
            summaryContent,
            StandardCharsets.UTF_8);

        mvc.perform(get("/results/summary.json"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(
                "application/json"));
    }

    @Test
    @DisplayName("GET /results/missing.json returns 404 when file absent")
    void missingFileReturns404() throws Exception {
        mvc.perform(get("/results/missing-file-that-does-not-exist.json"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /results/guids_en/pages/page-001.json returns 200 "
            + "when page file exists")
    void pageFileIsServedUnderNestedResultsPath() throws Exception {
        Path resultsDir = Path.of(
            System.getProperty("java.io.tmpdir"), "webconfig-test-results");
        Path pagesDir = resultsDir.resolve("guids_en").resolve("pages");
        Files.createDirectories(pagesDir);

        Files.writeString(
            pagesDir.resolve("page-001.json"),
            "[{\"identifier\":\"abc123\"}]",
            StandardCharsets.UTF_8);

        mvc.perform(get("/results/guids_en/pages/page-001.json"))
            .andExpect(status().isOk());
    }
}