/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package cessda.cmv.benchmark.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * Application-wide benchmark runner configuration loaded from
 * {@code application.yaml}.
 *
 * <p>
 * The web application always uses {@code dataDir} and
 * {@code resultsDir}. The optional {@code algorithm} and
 * {@code runner} values are used only by the standalone CLI entry
 * points, where there is no tenant context.
 * </p>
 */
@Component
@Validated
@ConfigurationProperties(prefix = "benchmark")
public class BenchmarkProperties {

    @NotBlank
    private String dataDir;

    @NotBlank
    private String resultsDir;

    private String algorithm;

    private String runner;

    /**
     * Pause, in milliseconds, observed before submitting each GUID
     * after the first within a batch during a benchmark run — see
     * {@code RunBenchmarkAssessment.DEFAULT_BACKOFF_BETWEEN_PROCESS_GUID_MS}.
     * {@code null} (the default when unset) leaves that compiled-in
     * default in place.
     */
    private Long backoffBetweenProcessGuidMs;

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public String getResultsDir() {
        return resultsDir;
    }

    public void setResultsDir(String resultsDir) {
        this.resultsDir = resultsDir;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public String getRunner() {
        return runner;
    }

    public void setRunner(String runner) {
        this.runner = runner;
    }

    public Long getBackoffBetweenProcessGuidMs() {
        return backoffBetweenProcessGuidMs;
    }

    public void setBackoffBetweenProcessGuidMs(Long backoffBetweenProcessGuidMs) {
        this.backoffBetweenProcessGuidMs = backoffBetweenProcessGuidMs;
    }

    public Path getDataDirPath() {
        return Paths.get(dataDir).toAbsolutePath().normalize();
    }

    public Path getResultsDirPath() {
        return Paths.get(resultsDir).toAbsolutePath().normalize();
    }
}
