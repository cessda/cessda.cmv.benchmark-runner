/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package cessda.cmv.benchmark.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configures static resource handling for the HTML dashboard.
 *
 * <p>The dashboard JavaScript fetches result files using paths relative
 * to the page URL, e.g.:</p>
 *
 * <pre>
 *   fetch('results/summary.json')
 *   fetch('results/guids_en/pages/page-001.json')
 * </pre>
 *
 * <p>These resolve to URLs under the {@code /results/} prefix. This
 * configuration maps that URL prefix to the results volume on disk so
 * Spring Boot serves the files without requiring them to be bundled
 * inside the JAR.</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${benchmark.results-dir:/results}")
    private String resultsDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Map  GET /results/**
        // to   file:<resultsDir>/
        //
        // e.g. /results/summary.json
        //   -> /results/summary.json  (Docker volume)
        //   -> ./results/summary.json (IDE / local run)
        registry
            .addResourceHandler("/results/**")
            .addResourceLocations("file:" + resultsDir + "/");
    }
}