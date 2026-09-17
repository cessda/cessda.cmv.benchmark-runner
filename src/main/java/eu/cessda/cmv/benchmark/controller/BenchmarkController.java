/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package eu.cessda.cmv.benchmark.controller;

import eu.cessda.cmv.benchmark.service.BenchmarkService;
import eu.cessda.cmv.benchmark.service.BenchmarkService.Branding;
import eu.cessda.cmv.benchmark.tenant.TenantProperties.TenantConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        @GetMapping({ "/config", "/api/config" })
        public Map<String, Map<String, String>> getConfig() {
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
        public Branding getTenantBranding() {
                return service.getTenantBranding();
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
        public Map<String, String> fetchIdentifiers(

                        @Parameter(description = "OAI-PMH base URL. " +
                                "Default: https://datacatalogue.cessda.eu/oai-pmh/v0/oai") @RequestParam(required = false) URI baseUrl,

                        @Parameter(description = "OAI-PMH verb used when listing identifiers. " +
                                        "Default: ListIdentifiers") @RequestParam(required = false) String verb,

                        @Parameter(description = "Metadata prefix embedded in output GetRecord URLs. " +
                                        "Default: oai_ddi25") @RequestParam(required = false) String metadataPrefix,

                        @Parameter(description = "Comma-separated list of sets to fetch. " +
                                        "Default: de,el,en,fi,fr,hr,nl,sl,sl-SI,sv") @RequestParam(required = false) String sets,

                        @Parameter(description = "Fetch identifiers for a single named set only. " +
                                        "When supplied, the 'sets' parameter is ignored.") @RequestParam(required = false) String fetchSet

        ) throws IOException, InterruptedException {
                String message = service.fetchIdentifiers(baseUrl, verb, metadataPrefix, sets, fetchSet);
                return response("ok", message);
        }

        // -------------------------------------------------------------------------
        // 1a. GET /api/fetch-identifiers/defaults
        // -------------------------------------------------------------------------

        @Operation(summary = "Get default OAI-PMH base URL", description = "Returns the OAI-PMH base URL that would be used for the "
                        +
                        "current tenant if fetch-identifiers were called with no explicit " +
                        "override. Used by the dashboard to populate the \"Fetch " +
                        "identifiers\" page's URL field before the operator optionally " +
                        "changes it for this run.", responses = {
                                        @ApiResponse(responseCode = "200", description = "Default retrieved successfully", content = @Content(schema = @Schema(example = "{\"baseUrl\":\"https://datacatalogue.cessda.eu/oai-pmh/v0/oai\"}"))),
                                        @ApiResponse(responseCode = "500", description = "Failed to resolve default")
                        })
        @GetMapping("/fetch-identifiers/defaults")
        public ResponseEntity<Map<String, String>> getFetchIdentifiersDefaults() {
                try {
                        Map<String, String> body = new LinkedHashMap<>();
                        body.put("baseUrl", service.getDefaultOaiPmhBaseUrl());
                        return ResponseEntity.ok(body);
                } catch (Exception e) {
                        return ResponseEntity.internalServerError()
                                        .body(response("error", e.getMessage()));
                }
        }

        // -------------------------------------------------------------------------
        // 1b. GET /api/fetch-identifiers/sets
        // -------------------------------------------------------------------------

        @Operation(summary = "List sets available from an OAI-PMH endpoint", description = "Calls verb=ListSets against the given (or, if omitted, "
                        +
                        "the current tenant's default) OAI-PMH endpoint and returns every " +
                        "set it reports, live. There is no static or compiled-in " +
                        "fallback list: a hand-maintained copy inevitably drifts out of " +
                        "sync with the actual repository, so the dashboard's checkable " +
                        "set list always reflects exactly what that specific endpoint " +
                        "currently offers, including after the URL is overridden for a " +
                        "one-off run.", responses = {
                                        @ApiResponse(responseCode = "200", description = "Sets listed successfully", content = @Content(schema = @Schema(example = "{\"sets\":[{\"setSpec\":\"language:hr\",\"setName\":\"Language hr\"}]}"))),
                                        @ApiResponse(responseCode = "500", description = "Failed to list sets")
                        })
        @GetMapping("/fetch-identifiers/sets")
        public ResponseEntity<Map<String, Object>> getFetchIdentifiersSets(

                        @Parameter(description = "OAI-PMH base URL, for this request only. " +
                                        "Default: the current tenant's configured oai-pmh-base-url.") @RequestParam(required = false) String baseUrl,

                        @Parameter(description = "OAI-PMH verb. Default: ListIdentifiers " +
                                        "(unused by ListSets itself, accepted for consistency with " +
                                        "the other fetch-identifiers endpoints).") @RequestParam(required = false) String verb

        ) {
                try {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("sets", service.listAvailableSets(baseUrl, verb));
                        return ResponseEntity.ok(body);
                } catch (Exception e) {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("status", "error");
                        body.put("message", e.getMessage());
                        return ResponseEntity.internalServerError().body(body);
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
        public Map<String, URI> getRunAssessmentDefaults() {
                URI[] defaults = service.getDefaultAlgorithmAndRunner();
                Map<String, URI> body = new LinkedHashMap<>();
                body.put("algorithm", defaults[0]);
                body.put("runner", defaults[1]);
                return body;
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
        public Map<String, List<String>> listGuidFiles() throws IOException {
                Map<String, List<String>> body = new LinkedHashMap<>();
                body.put("files", service.listGuidFiles());
                return body;
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
        public Map<String, String> runAssessment(

                        @Parameter(description = "Algorithm runner URI. " +
                                "Configurable via 'benchmark.algorithm' property.") @RequestParam(required = false) URI spreadsheetUri,

                        @Parameter(description = "FAIR Champion API URI to POST GUIDs to. " +
                                "Configurable via 'benchmark.runner' property.") @RequestParam(required = false) URI runnerUri,

                        @Parameter(description = "Name of a specific guids_*.txt file to process " +
                                        "(e.g. guids_de.txt). Ignored when 'guidFiles', 'guid', or " +
                                        "'processAll' is set.") @RequestParam(required = false) String guidFile,

                        @Parameter(description = "One or more specific guids_*.txt filenames to " +
                                        "process, as selected by the operator (e.g. " +
                                        "guids_de.txt, guids_en.txt). Takes priority over " +
                                "'guidFile' and 'processAll', but not over 'guid'.") @RequestParam(required = false) List<String> guidFiles,

                        @Parameter(description = "A single full OAI-PMH GetRecord URL to assess directly. " +
                                        "Takes priority over 'guidFiles', 'guidFile', and 'processAll'.") @RequestParam(required = false) String guid,

                        @Parameter(description = "When true, process guids_*.txt files for all default sets " +
                                        "(de, el, en, fi, fr, hr, nl, sl, sl-SI, sv). Ignored when " +
                                        "'guidFiles' is set. Default: false") @RequestParam(required = false, defaultValue = "false") boolean processAll

        ) throws IOException, InterruptedException {
                        String message = service.runAssessment(
                                        spreadsheetUri, runnerUri,
                                        guidFile, guidFiles, guid, processAll);
                return response("ok", message);
        }

        // -------------------------------------------------------------------------
        // 5. POST /api/generate-manifest
        // -------------------------------------------------------------------------

        @Operation(summary = "Generate dashboard manifest", description = "Reads JSON result files from the results volume and produces "
                        +
                        "results/summary.json and paginated results/guids_<set>/pages/page-NNN.json " +
                        "files consumed by the HTML dashboard. " +
                        "Equivalent to running GenerateManifest from the command line or " +
                        "the first step of start-dashboard.sh.", responses = {
                                        @ApiResponse(responseCode = "200", description = "Manifest generated successfully", content = @Content(schema = @Schema(example = "{\"status\":\"ok\",\"message\":\"Manifest generated in: /results\"}"))),
                                        @ApiResponse(responseCode = "500", description = "Manifest generation failed")
                        })
        @PostMapping("/generate-manifest")
        public Map<String, String> generateManifest(

                        @Parameter(description = "Override the results directory path. " +
                                        "Default: the 'benchmark.results-dir' volume (/results).") @RequestParam(required = false) String resultsDir

        ) throws IOException {
                String message = service.generateManifest(resultsDir);
                return response("ok", message);
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

        @ExceptionHandler
        @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
        private Map<String, String> handleException(Exception e) {
                return response("error", e.getMessage());
        }
}