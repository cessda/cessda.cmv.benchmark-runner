/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package cessda.cmv.benchmark.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cessda.cmv.benchmark.service.BenchmarkService;

/**
 * Unit tests for {@link BenchmarkController}.
 *
 * <p>Uses the {@code @WebMvcTest} slice so only the MVC layer is
 * loaded. {@link BenchmarkService} is replaced with a Mockito mock,
 * meaning no real HTTP calls or file I/O occur during these tests.</p>
 *
 * <p>Each nested class groups the tests for one endpoint. Within each
 * group the happy path is tested first, followed by error cases.</p>
 */
@WebMvcTest(BenchmarkController.class)
class BenchmarkControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BenchmarkService service;

    // -------------------------------------------------------------------------
    // POST /api/fetch-identifiers
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /api/fetch-identifiers")
    class FetchIdentifiers {

        @Test
        @DisplayName("Returns 200 with ok status when called with no parameters")
        void defaultParametersReturn200() throws Exception {
            when(service.fetchIdentifiers(
                    isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn("Fetched identifiers for 10 set(s) -> /data");

            mvc.perform(post("/api/fetch-identifiers"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                    MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status", is("ok")))
                .andExpect(jsonPath("$.message",
                    is("Fetched identifiers for 10 set(s) -> /data")));

            verify(service).fetchIdentifiers(
                null, null, null, null, null);
        }

        @Test
        @DisplayName("Passes fetchSet parameter to service")
        void singleSetParameterIsForwarded() throws Exception {
            when(service.fetchIdentifiers(
                    isNull(), isNull(), isNull(), isNull(), eq("en")))
                .thenReturn("Fetched identifiers for set: en -> /data/guids_en.txt");

            mvc.perform(post("/api/fetch-identifiers")
                    .param("fetchSet", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ok")));

            verify(service).fetchIdentifiers(
                null, null, null, null, "en");
        }

        @Test
        @DisplayName("Passes comma-separated sets parameter to service")
        void multipleSetsParameterIsForwarded() throws Exception {
            when(service.fetchIdentifiers(
                    isNull(), isNull(), isNull(), eq("de,en,fr"), isNull()))
                .thenReturn("Fetched identifiers for 3 set(s) -> /data");

            mvc.perform(post("/api/fetch-identifiers")
                    .param("sets", "de,en,fr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ok")));

            verify(service).fetchIdentifiers(
                null, null, null, "de,en,fr", null);
        }

        @Test
        @DisplayName("Passes all optional parameters to service")
        void allOptionalParametersAreForwarded() throws Exception {
            when(service.fetchIdentifiers(
                    eq("https://example.org/oai"),
                    eq("ListIdentifiers"),
                    eq("oai_dc"),
                    isNull(),
                    isNull()))
                .thenReturn("Fetched identifiers for 10 set(s) -> /data");

            mvc.perform(post("/api/fetch-identifiers")
                    .param("baseUrl",        "https://example.org/oai")
                    .param("verb",           "ListIdentifiers")
                    .param("metadataPrefix", "oai_dc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ok")));
        }

        @Test
        @DisplayName("Returns 500 with error status when service throws IOException")
        void serviceExceptionReturns500() throws Exception {
            when(service.fetchIdentifiers(
                    any(), any(), any(), any(), any()))
                .thenThrow(new IOException("Connection refused"));

            mvc.perform(post("/api/fetch-identifiers"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status", is("error")))
                .andExpect(jsonPath("$.message", is("Connection refused")));
        }

        @Test
        @DisplayName("Returns 500 with error status when service throws "
                + "InterruptedException")
        void interruptedExceptionReturns500() throws Exception {
            when(service.fetchIdentifiers(
                    any(), any(), any(), any(), any()))
                .thenThrow(new InterruptedException("Interrupted"));

            mvc.perform(post("/api/fetch-identifiers"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status", is("error")));
        }
    }

    // -------------------------------------------------------------------------
    // POST /api/run-assessment
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /api/run-assessment")
    class RunAssessment {

        @Test
        @DisplayName("Returns 200 with ok status when called with no parameters")
        void defaultParametersReturn200() throws Exception {
            when(service.runAssessment(
                    isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(
                    "Processed default file: guids_hr.txt"
                    + " -> results written to /results");

            mvc.perform(post("/api/run-assessment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ok")))
                .andExpect(jsonPath("$.message",
                    is("Processed default file: guids_hr.txt"
                        + " -> results written to /results")));

            verify(service).runAssessment(null, null, null, false);
        }

        @Test
        @DisplayName("Passes processAll=true to service")
        void processAllParameterIsForwarded() throws Exception {
            when(service.runAssessment(
                    isNull(), isNull(), isNull(), eq(true)))
                .thenReturn(
                    "Processed all default set files from /data"
                    + " -> results written to /results");

            mvc.perform(post("/api/run-assessment")
                    .param("processAll", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ok")));

            verify(service).runAssessment(null, null, null, true);
        }

        @Test
        @DisplayName("Passes guidFile parameter to service")
        void guidFileParameterIsForwarded() throws Exception {
            when(service.runAssessment(
                    isNull(), eq("guids_de.txt"), isNull(), eq(false)))
                .thenReturn(
                    "Processed file: /data/guids_de.txt"
                    + " -> results written to /results");

            mvc.perform(post("/api/run-assessment")
                    .param("guidFile", "guids_de.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ok")));

            verify(service).runAssessment(
                null, "guids_de.txt", null, false);
        }

        @Test
        @DisplayName("Passes single guid URL parameter to service")
        void singleGuidParameterIsForwarded() throws Exception {
            String guidUrl =
                "https://datacatalogue.cessda.eu/oai-pmh/v0/oai"
                + "?verb=GetRecord&metadataPrefix=oai_ddi25"
                + "&identifier=abc123";

            when(service.runAssessment(
                    isNull(), isNull(), eq(guidUrl), eq(false)))
                .thenReturn(
                    "Processed single GUID: " + guidUrl
                    + " -> results written to /results");

            mvc.perform(post("/api/run-assessment")
                    .param("guid", guidUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ok")));

            verify(service).runAssessment(null, null, guidUrl, false);
        }

        @Test
        @DisplayName("Passes custom spreadsheetUri to service")
        void spreadsheetUriParameterIsForwarded() throws Exception {
            String customUri = "https://example.org/champion/assess";
            when(service.runAssessment(
                    eq(customUri), isNull(), isNull(), eq(false)))
                .thenReturn("Processed default file: guids_hr.txt"
                    + " -> results written to /results");

            mvc.perform(post("/api/run-assessment")
                    .param("spreadsheetUri", customUri))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ok")));
        }

        @Test
        @DisplayName("Returns 500 with error status when service throws IOException")
        void serviceExceptionReturns500() throws Exception {
            when(service.runAssessment(any(), any(), any(), anyBoolean()))
                .thenThrow(new IOException("File not found: guids_hr.txt"));

            mvc.perform(post("/api/run-assessment"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status", is("error")))
                .andExpect(jsonPath("$.message",
                    is("File not found: guids_hr.txt")));
        }
    }

    // -------------------------------------------------------------------------
    // POST /api/generate-manifest
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /api/generate-manifest")
    class GenerateManifest {

        @Test
        @DisplayName("Returns 200 with ok status when called with no parameters")
        void defaultParametersReturn200() throws Exception {
            when(service.generateManifest(isNull()))
                .thenReturn("Manifest generated in: /results");

            mvc.perform(post("/api/generate-manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ok")))
                .andExpect(jsonPath("$.message",
                    is("Manifest generated in: /results")));

            verify(service).generateManifest(null);
        }

        @Test
        @DisplayName("Passes resultsDir override parameter to service")
        void resultsDirOverrideIsForwarded() throws Exception {
            when(service.generateManifest(eq("/custom/results")))
                .thenReturn("Manifest generated in: /custom/results");

            mvc.perform(post("/api/generate-manifest")
                    .param("resultsDir", "/custom/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ok")))
                .andExpect(jsonPath("$.message",
                    is("Manifest generated in: /custom/results")));

            verify(service).generateManifest("/custom/results");
        }

        @Test
        @DisplayName("Returns 500 with error status when results directory "
                + "does not exist")
        void missingResultsDirReturns500() throws Exception {
            when(service.generateManifest(any()))
                .thenThrow(new IOException(
                    "Results directory not found: /results"));

            mvc.perform(post("/api/generate-manifest"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status", is("error")))
                .andExpect(jsonPath("$.message",
                    is("Results directory not found: /results")));
        }

        @Test
        @DisplayName("Returns 500 with error status when service throws IOException")
        void serviceExceptionReturns500() throws Exception {
            when(service.generateManifest(any()))
                .thenThrow(new IOException("Unreadable result file"));

            mvc.perform(post("/api/generate-manifest"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status", is("error")));
        }
    }
}