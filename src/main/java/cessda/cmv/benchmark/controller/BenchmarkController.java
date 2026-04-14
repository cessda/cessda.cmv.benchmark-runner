/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package cessda.cmv.benchmark.controller;

import cessda.cmv.benchmark.service.BenchmarkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST controller exposing the three benchmark pipeline operations as HTTP
 * POST endpoints.
 *
 * <p>All parameters mirror the CLI flags of the original command-line classes.
 * All parameters are optional; defaults match the CLI defaults.</p>
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Benchmark Pipeline",
     description = "Endpoints for fetching OAI-PMH identifiers, running FAIR " +
                   "benchmark assessments, and generating the dashboard manifest.")
public class BenchmarkController {

    private final BenchmarkService service;

    public BenchmarkController(BenchmarkService service) {
        this.service = service;
    }

    // -------------------------------------------------------------------------
    // 1. POST /api/fetch-identifiers
    // -------------------------------------------------------------------------

    @Operation(
        summary     = "Fetch OAI-PMH identifiers",
        description = "Fetches record identifiers from an OAI-PMH endpoint and writes " +
                      "guids_<set>.txt files to the data volume. " +
                      "Equivalent to running GetOaiPmhIdentifiers from the command line.",
        responses   = {
            @ApiResponse(responseCode = "200", description = "Identifiers fetched successfully",
                content = @Content(schema = @Schema(example =
                    "{\"status\":\"ok\",\"message\":\"Fetched identifiers for 10 set(s)\"}"))),
            @ApiResponse(responseCode = "500", description = "Fetch failed")
        }
    )
    @PostMapping("/fetch-identifiers")
    public ResponseEntity<Map<String, String>> fetchIdentifiers(

        @Parameter(description = "OAI-PMH base URL. " +
                   "Default: https://datacatalogue.cessda.eu/oai-pmh/v0/oai")
        @RequestParam(required = false) String baseUrl,

        @Parameter(description = "OAI-PMH verb used when listing identifiers. " +
                   "Default: ListIdentifiers")
        @RequestParam(required = false) String verb,

        @Parameter(description = "Metadata prefix embedded in output GetRecord URLs. " +
                   "Default: oai_ddi25")
        @RequestParam(required = false) String metadataPrefix,

        @Parameter(description = "Comma-separated list of sets to fetch. " +
                   "Default: de,el,en,fi,fr,hr,nl,sl,sl-SI,sv")
        @RequestParam(required = false) String sets,

        @Parameter(description = "Fetch identifiers for a single named set only. " +
                   "When supplied, the 'sets' parameter is ignored.")
        @RequestParam(required = false) String fetchSet

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
    // 2. POST /api/run-assessment
    // -------------------------------------------------------------------------

    @Operation(
        summary     = "Run FAIR benchmark assessment",
        description = "Reads guids_*.txt files from the data volume, posts each GetRecord " +
                      "URL to the FAIR Champion API, and writes JSON result files to the " +
                      "results volume. Equivalent to running RunBenchmarkAssessment from " +
                      "the command line.",
        responses   = {
            @ApiResponse(responseCode = "200", description = "Assessment completed",
                content = @Content(schema = @Schema(example =
                    "{\"status\":\"ok\",\"message\":\"Processed all default set files\"}"))),
            @ApiResponse(responseCode = "500", description = "Assessment failed")
        }
    )
    @PostMapping("/run-assessment")
    public ResponseEntity<Map<String, String>> runAssessment(

        @Parameter(description = "FAIR Champion API URI to POST GUIDs to. " +
                   "Default: https://tools.ostrails.eu/champion/assess/algorithm/d/1Nk0vM4y...")
        @RequestParam(required = false) String spreadsheetUri,

        @Parameter(description = "Name of a specific guids_*.txt file to process " +
                   "(e.g. guids_de.txt). Ignored when 'guid' or 'processAll' is set.")
        @RequestParam(required = false) String guidFile,

        @Parameter(description = "A single full OAI-PMH GetRecord URL to assess directly. " +
                   "Takes priority over 'guidFile' and 'processAll'.")
        @RequestParam(required = false) String guid,

        @Parameter(description = "When true, process guids_*.txt files for all default sets " +
                   "(de, el, en, fi, fr, hr, nl, sl, sl-SI, sv). Default: false")
        @RequestParam(required = false, defaultValue = "false") boolean processAll

    ) {
        try {
            String message = service.runAssessment(spreadsheetUri, guidFile, guid, processAll);
            return ResponseEntity.ok(response("ok", message));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(response("error", e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // 3. POST /api/generate-manifest
    // -------------------------------------------------------------------------

    @Operation(
        summary     = "Generate dashboard manifest",
        description = "Reads JSON result files from the results volume and produces " +
                      "results/summary.json and paginated results/guids_<lang>/pages/page-NNN.json " +
                      "files consumed by the HTML dashboard. " +
                      "Equivalent to running GenerateManifest from the command line or " +
                      "the first step of start-dashboard.sh.",
        responses   = {
            @ApiResponse(responseCode = "200", description = "Manifest generated successfully",
                content = @Content(schema = @Schema(example =
                    "{\"status\":\"ok\",\"message\":\"Manifest generated in: /results\"}"))),
            @ApiResponse(responseCode = "500", description = "Manifest generation failed")
        }
    )
    @PostMapping("/generate-manifest")
    public ResponseEntity<Map<String, String>> generateManifest(

        @Parameter(description = "Override the results directory path. " +
                   "Default: the 'benchmark.results-dir' volume (/results).")
        @RequestParam(required = false) String resultsDir

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
        body.put("status",  status);
        body.put("message", message);
        return body;
    }
}
