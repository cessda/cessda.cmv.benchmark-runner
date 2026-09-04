/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package cessda.cmv.benchmark.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.nio.file.Path;

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
@ConfigurationProperties(prefix = "benchmark")
@Validated
public class BenchmarkProperties {

    @NotNull
    private final Path dataDir;

    @NotNull
    private final Path resultsDir;

    private final URI algorithm;

    private final URI runner;

    public BenchmarkProperties(Path dataDir, Path resultsDir, URI algorithm, URI runner) {
        this.dataDir = dataDir;
        this.resultsDir = resultsDir;
        this.algorithm = algorithm;
        this.runner = runner;
    }

    public Path getDataDir() {
        return dataDir;
    }

    public Path getResultsDir() {
        return resultsDir;
    }

    public URI getAlgorithm() {
        return algorithm;
    }

    public URI getRunner() {
        return runner;
    }
}
