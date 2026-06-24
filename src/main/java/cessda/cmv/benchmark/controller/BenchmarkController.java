/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package cessda.cmv.benchmark.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cessda.cmv.benchmark.service.BenchmarkService;
import cessda.cmv.benchmark.service.BenchmarkService.Branding;
import cessda.cmv.benchmark.tenant.TenantProperties.TenantConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST controller exposing the three benchmark pipeline operations as HTTP
 * POST endpoints.
 *
 * <p>
 * All parameters mirror the CLI flags of the original command-line classes.
 * All parameters are optional; defaults match the CLI defaults.
 * </p>
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Benchmark Pipeline", description = "Endpoints for fetching OAI-PMH identifiers, running FAIR " +
                "benchmark assessments, and generating the dashboard manifest.")
public class BenchmarkController {

        private final BenchmarkService service;

        public BenchmarkController(BenchmarkService service) {
                this.service = service;
        }

        // -------------------------------------------------------------------------
        // GET /api/config
        // -------------------------------------------------------------------------

        @GetMapping("/api/config")
        public Map<String, Object> getConfig() {
                TenantConfig cfg = service.getCurrentTenantConfig();
                return Map.of(
                                "setNames", cfg.getSetNames(),
                                "fairMap", cfg.getFairMap());
        }

        // -------------------------------------------------------------------------
        // GET /api/tenant/branding
        // -------------------------------------------------------------------------

        @Operation(summary = "Get tenant branding", description = "Returns the display title and footer text configured for "
                        +
                        "the current tenant, for use in the dashboard UI.", responses = {
                                        @ApiResponse(responseCode = "200", description = "Branding retrieved", content = @Content(schema = @Schema(example = "{\"title\":\"CESSDA · Assessment Results\",\"footer\":\"CESSDA FAIR Benchmark\"}")))
                        })
        @GetMapping("/tenant/branding")
        public ResponseEntity<Map<String, String>> getTenantBranding() {
                try {
                        Branding branding = service.getTenantBranding();
                        Map<String, String> body = new LinkedHashMap<>();
                        body.put("title", branding.title());
                        body.put("footer", branding.footer());
                        return ResponseEntity.ok(body);
                } catch (Exception e) {
                        return ResponseEntity.internalServerError()
                                        .body(response("error", e.getMessage()));
                }
        }

        // -------------------------------------------------------------------------
        // 1. POST /api/fetch-identifiers
        // -------------------------------------------------------------------------

        @Operation(summary = "Fetch OAI-PMH identifiers", description = "Fetches record identifiers from an OAI-PMH endpoint and writes "
                        +
                        "guids_<set>.txt files to the data volume. " +
                        "Equivalent to running GetOaiPmhIdentifiers from the command line.", responses = {
                                        @ApiResponse(responseCode = "200", description = "Identifiers fetched successfully", content = @Content(schema = @Schema(example = "{\"status\":\"ok\",\"message\":\"Fetched identifiers for 10 set(s)\"}"))),
                                        @ApiResponse(responseCode = "500", description = "Fetch failed")
                        })
        @PostMapping("/fetch-identifiers")
        public ResponseEntity<Map<String, String>> fetchIdentifiers(

                        @Parameter(description = "OAI-PMH base URL. " +
                                        "Default: https://datacatalogue.cessda.eu/oai-pmh/v0/oai") @RequestParam(required = false) String baseUrl,

                        @Parameter(description = "OAI-PMH verb used when listing identifiers. " +
                                        "Default: ListIdentifiers") @RequestParam(required = false) String verb,

                        @Parameter(description = "Metadata prefix embedded in output GetRecord URLs. " +
                                        "Default: oai_ddi25") @RequestParam(required = false) String metadataPrefix,

                        @Parameter(description = "Comma-separated list of sets to fetch. " +
                                        "Default: de,el,en,fi,fr,hr,nl,sl,sl-SI,sv") @RequestParam(required = false) String sets,

                        @Parameter(description = "Fetch identifiers for a single named set only. " +
                                        "When supplied, the 'sets' parameter is ignored.") @RequestParam(required = false) String fetchSet

        ) {
                try {
                        String message = service.fetchIdentifiers(baseUrl, verb, metadataPrefix, sets, fetchSet);
                        return ResponseEntity.ok(response("ok", message));
                } catch (Exception e) {
                        return ResponseEntity.internalServerError()
                                        .body(response("error", e.getMessage()));
                }
        }

        // -------------------------------------------------------------------------
        // 2. GET /api/run-assessment/defaults
        // -------------------------------------------------------------------------

        @Operation(summary = "Get default algorithm and runner URIs", description = "Returns the algorithm and runner URIs that would be used "
                        +
                        "for the current tenant if run-assessment were called with " +
                        "no explicit overrides. Used by the dashboard to populate " +
                        "the confirmation dialog shown before triggering an " +
                        "assessment run.", responses = {
                                        @ApiResponse(responseCode = "200", description = "Defaults retrieved successfully", content = @Content(schema = @Schema(example = "{\"algorithm\":\"https://...\",\"runner\":\"https://...\"}"))),
                                        @ApiResponse(responseCode = "500", description = "Failed to resolve defaults")
                        })
        @GetMapping("/run-assessment/defaults")
        public ResponseEntity<Map<String, String>> getRunAssessmentDefaults() {
                try {
                        String[] defaults = service.getDefaultAlgorithmAndRunner();
                        Map<String, String> body = new LinkedHashMap<>();
                        body.put("algorithm", defaults[0]);
                        body.put("runner", defaults[1]);
                        return ResponseEntity.ok(body);
                } catch (Exception e) {
                        return ResponseEntity.internalServerError()
                                        .body(response("error", e.getMessage()));
                }
        }

        // -------------------------------------------------------------------------
        // 3. GET /api/run-assessment/guid-files
        // -------------------------------------------------------------------------

        @Operation(summary = "List available guids_*.txt files", description = "Lists the guids_*.txt files present in the current "
                        +
                        "tenant's data directory, for selecting which sets to " +
                        "assess. Used by the dashboard to populate the " +
                        "checkable list shown in the run-assessment " +
                        "confirmation dialog.", responses = {
                                        @ApiResponse(responseCode = "200", description = "Files listed successfully", content = @Content(schema = @Schema(example = "{\"files\":[\"guids_de.txt\",\"guids_en.txt\"]}"))),
                                        @ApiResponse(responseCode = "500", description = "Failed to list files")
                        })
        @GetMapping("/run-assessment/guid-files")
        public ResponseEntity<Map<String, Object>> listGuidFiles() {
                try {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("files", service.listGuidFiles());
                        return ResponseEntity.ok(body);
                } catch (Exception e) {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("status", "error");
                        body.put("message", e.getMessage());
                        return ResponseEntity.internalServerError().body(body);
                }
        }

        // -------------------------------------------------------------------------
        // 4. POST /api/run-assessment
        // -------------------------------------------------------------------------

        @Operation(summary = "Run FAIR benchmark assessment", description = "Reads guids_*.txt files from the data volume, posts each GetRecord "
                        +
                        "URL to the FAIR Champion API, and writes JSON result files to the " +
                        "results volume. Equivalent to running RunBenchmarkAssessment from " +
                        "the command line.", responses = {
                                        @ApiResponse(responseCode = "200", description = "Assessment completed", content = @Content(schema = @Schema(example = "{\"status\":\"ok\",\"message\":\"Processed all default set files\"}"))),
                                        @ApiResponse(responseCode = "500", description = "Assessment failed")
                        })
        @PostMapping("/run-assessment")
        public ResponseEntity<Map<String, String>> runAssessment(

                        @Parameter(description = "Algorithm runner URI. " +
                                        "Configurable via 'benchmark.algorithm' property.") @RequestParam(required = false) String spreadsheetUri,

                        @Parameter(description = "FAIR Champion API URI to POST GUIDs to. " +
                                        "Configurable via 'benchmark.runner' property.") @RequestParam(required = false) String runnerUri,

                        @Parameter(description = "Name of a specific guids_*.txt file to process " +
                                        "(e.g. guids_de.txt). Ignored when 'guidFiles', 'guid', or " +
                                        "'processAll' is set.") @RequestParam(required = false) String guidFile,

                        @Parameter(description = "One or more specific guids_*.txt filenames to " +
                                        "process, as selected by the operator (e.g. " +
                                        "guids_de.txt, guids_en.txt). Takes priority over " +
                                        "'guidFile' and 'processAll', but not over 'guid'.") @RequestParam(required = false) java.util.List<String> guidFiles,

                        @Parameter(description = "A single full OAI-PMH GetRecord URL to assess directly. " +
                                        "Takes priority over 'guidFiles', 'guidFile', and 'processAll'.") @RequestParam(required = false) String guid,

                        @Parameter(description = "When true, process guids_*.txt files for all default sets " +
                                        "(de, el, en, fi, fr, hr, nl, sl, sl-SI, sv). Ignored when " +
                                        "'guidFiles' is set. Default: false") @RequestParam(required = false, defaultValue = "false") boolean processAll

        ) {
                try {
                        String message = service.runAssessment(
                                        spreadsheetUri, runnerUri,
                                        guidFile, guidFiles, guid, processAll);
                        return ResponseEntity.ok(response("ok", message));
                } catch (Exception e) {
                        return ResponseEntity.internalServerError()
                                        .body(response("error", e.getMessage()));
                }
        }

        // -------------------------------------------------------------------------
        // 5. POST /api/generate-manifest
        // -------------------------------------------------------------------------

        @Operation(summary = "Generate dashboard manifest", description = "Reads JSON result files from the results volume and produces "
                        +
                        "results/summary.json and paginated results/guids_<lang>/pages/page-NNN.json " +
                        "files consumed by the HTML dashboard. " +
                        "Equivalent to running GenerateManifest from the command line or " +
                        "the first step of start-dashboard.sh.", responses = {
                                        @ApiResponse(responseCode = "200", description = "Manifest generated successfully", content = @Content(schema = @Schema(example = "{\"status\":\"ok\",\"message\":\"Manifest generated in: /results\"}"))),
                                        @ApiResponse(responseCode = "500", description = "Manifest generation failed")
                        })
        @PostMapping("/generate-manifest")
        public ResponseEntity<Map<String, String>> generateManifest(

                        @Parameter(description = "Override the results directory path. " +
                                        "Default: the 'benchmark.results-dir' volume (/results).") @RequestParam(required = false) String resultsDir

        ) {
                try {
                        String message = service.generateManifest(resultsDir);
                        return ResponseEntity.ok(response("ok", message));
                } catch (Exception e) {
                        return ResponseEntity.internalServerError()
                                        .body(response("error", e.getMessage()));
                }
        }

        // -------------------------------------------------------------------------
        // Helper
        // -------------------------------------------------------------------------

        private static Map<String, String> response(String status, String message) {
                Map<String, String> body = new LinkedHashMap<>();
                body.put("status", status);
                body.put("message", message);
                return body;
        }
}