/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package cessda.cmv.benchmark.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

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
     * Default pause observed before submitting each GUID after the
     * first within a batch during a benchmark run, applied when
     * {@code benchmark.backoff-between-process-guid-ms} is not
     * configured. Champion occasionally returns transient errors when
     * hit with bursts of concurrent requests, so this default pacing
     * is applied even when nothing is configured.
     */
    public static final Duration DEFAULT_BACKOFF_BETWEEN_PROCESS_GUID_MS = Duration.ofMillis(1_000);

    /**
     * Pause observed before submitting each GUID after the first
     * within a batch during a benchmark run. Defaults to
     * {@link #DEFAULT_BACKOFF_BETWEEN_PROCESS_GUID_MS} when
     * {@code benchmark.backoff-between-process-guid-ms} is not set.
     */
    private Duration backoffBetweenProcessGuidMs = DEFAULT_BACKOFF_BETWEEN_PROCESS_GUID_MS;

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

    public Duration getBackoffBetweenProcessGuidMs() {
        return backoffBetweenProcessGuidMs;
    }

    public void setBackoffBetweenProcessGuidMs(Duration backoffBetweenProcessGuidMs) {
        this.backoffBetweenProcessGuidMs = backoffBetweenProcessGuidMs;
    }

    public Path getDataDirPath() {
        return Paths.get(dataDir).toAbsolutePath().normalize();
    }

    public Path getResultsDirPath() {
        return Paths.get(resultsDir).toAbsolutePath().normalize();
    }
}
