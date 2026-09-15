/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package eu.cessda.cmv.benchmark;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.ParseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GetOaiPmhIdentifiers}.
 *
 * <p>HTTP calls are not made; all tests exercise parsing, URL
 * construction, file writing, and CLI argument handling using
 * in-memory data or temporary files.</p>
 */
class GetOaiPmhIdentifiersTest {

    // ── Fixture ──────────────────────────────────────────────────────────────

    /** Shared temporary directory injected by JUnit for the setUp client. */
    @TempDir
    Path tempDir;

    private GetOaiPmhIdentifiers client;

    @BeforeEach
    void setUp() {
        client = new GetOaiPmhIdentifiers(
                GetOaiPmhIdentifiers.DEFAULT_OAI_PMH_BASE_URL,
                GetOaiPmhIdentifiers.DEFAULT_VERB,
                GetOaiPmhIdentifiers.DEFAULT_METADATA_PREFIX,
                tempDir);
    }

    @AfterEach
    void tearDown() {
        client = null;
    }

    // ── Constants ────────────────────────────────────────────────────────────

    @Test
    void defaultVerbIsListIdentifiers() {
        assertEquals("ListIdentifiers", GetOaiPmhIdentifiers.DEFAULT_VERB);
    }

    @Test
    void defaultMetadataPrefixIsOaiDdi25() {
        assertEquals("oai_ddi25", GetOaiPmhIdentifiers.DEFAULT_METADATA_PREFIX);
    }

    @Test
    void defaultSetsContainsTenSets() {
        assertEquals(10, GetOaiPmhIdentifiers.DEFAULT_SETS.size());
    }

    @Test
    void defaultSetsContainsExpectedSetCodes() {
        List<String> sets = GetOaiPmhIdentifiers.DEFAULT_SETS;
        assertAll(
                () -> assertTrue(sets.contains("de")),
                () -> assertTrue(sets.contains("en")),
                () -> assertTrue(sets.contains("fr")),
                () -> assertTrue(sets.contains("sl-SI"))
        );
    }

    // ── buildGetRecordUrl ────────────────────────────────────────────────────

    @Test
    void buildGetRecordUrlContainsVerbGetRecord() {
        URI url = client.buildGetRecordUrl("abc123");
        assertTrue(url.getQuery().contains("verb=GetRecord"),
                "URL must contain verb=GetRecord");
    }

    @Test
    void buildGetRecordUrlContainsMetadataPrefix() {
        URI url = client.buildGetRecordUrl("abc123");
        assertTrue(url.getQuery().contains("metadataPrefix=oai_ddi25"),
                "URL must contain the default metadata prefix");
    }

    @Test
    void buildGetRecordUrlContainsEncodedIdentifier() {
        URI url = client.buildGetRecordUrl("abc123");
        assertTrue(url.getQuery().contains("identifier=abc123"),
                "URL must contain the identifier");
    }

    @Test
    void buildGetRecordUrlEncodesSpecialCharactersInIdentifier() {
        URI url = client.buildGetRecordUrl("id with spaces & more");
        assertFalse(url.getQuery().contains(" "),
                "URL must not contain raw spaces");
        assertFalse(url.toString().contains("&more"),
                "URL must not contain unencoded ampersand in identifier");
    }

    @Test
    void buildGetRecordUrlStartsWithBaseUrl() {
        String url = client.buildGetRecordUrl("id1").toString();
        assertTrue(url.startsWith(GetOaiPmhIdentifiers.DEFAULT_OAI_PMH_BASE_URL.toString()),
                "URL must start with the configured base URL");
    }

    @Test
    void buildGetRecordUrlWithCustomBaseUrl(@TempDir Path dir) {
        GetOaiPmhIdentifiers custom = new GetOaiPmhIdentifiers(
                URI.create("https://example.org/oai"), "ListIdentifiers", "oai_dc", dir);
        URI url = custom.buildGetRecordUrl("xyz");
        assertTrue(url.toString().startsWith("https://example.org/oai"),
                "URL must use the custom base URL");
        assertTrue(url.getQuery().contains("metadataPrefix=oai_dc"),
                "URL must use the custom metadata prefix");
    }

    @Test
    void buildGetRecordUrlWithEmptyIdentifierProducesValidUrl() {
        URI url = client.buildGetRecordUrl("");
        assertTrue(url.getQuery().contains("identifier="),
                "URL must still contain the identifier parameter key");
    }

    // ── parseArgs ────────────────────────────────────────────────────────────

    @Test
    void parseArgsWithNoArgumentsReturnsDefaults() throws ParseException {
        CommandLine cmd = GetOaiPmhIdentifiers.parseArgs();
        assertFalse(cmd.hasOption("fetch-set"),
                "fetch-set must not be set when no args are given");
        assertFalse(cmd.hasOption("fetch-all-sets"),
                "fetch-all-sets must not be set when no args are given");
    }

    @Test
    void parseArgsRecognisesFetchSetShortOption() throws ParseException {
        CommandLine cmd = GetOaiPmhIdentifiers.parseArgs("-s", "de");
        assertTrue(cmd.hasOption("fetch-set"));
        assertEquals("de", cmd.getOptionValue("fetch-set"));
    }

    @Test
    void parseArgsRecognisesFetchSetLongOption() throws ParseException {
        CommandLine cmd = GetOaiPmhIdentifiers.parseArgs("--fetch-set", "en");
        assertTrue(cmd.hasOption("fetch-set"));
        assertEquals("en", cmd.getOptionValue("fetch-set"));
    }

    @Test
    void parseArgsRecognisesFetchAllSetsShortOption() throws ParseException {
        CommandLine cmd = GetOaiPmhIdentifiers.parseArgs("-F");
        assertTrue(cmd.hasOption("fetch-all-sets"));
    }

    @Test
    void parseArgsRecognisesBaseUrlLongOption() throws ParseException {
        CommandLine cmd = GetOaiPmhIdentifiers.parseArgs(
                "--oai-pmh-base-url", "https://example.org/oai");
        assertEquals("https://example.org/oai",
                cmd.getOptionValue("oai-pmh-base-url"));
    }

    @Test
    void parseArgsRecognisesMetadataPrefixShortOption() throws ParseException {
        CommandLine cmd = GetOaiPmhIdentifiers.parseArgs(
                "-m", "oai_dc");
        assertEquals("oai_dc", cmd.getOptionValue("metadata-prefix"));
    }

    @Test
    void parseArgsRecognisesCustomSetsOption() throws ParseException {
        CommandLine cmd = GetOaiPmhIdentifiers.parseArgs(
                "-S", "de,en,fr");
        assertEquals("de,en,fr", cmd.getOptionValue("sets"));
    }

    @Test
    void parseArgsThrowsOnUnrecognisedOption() {
        assertThrows(ParseException.class,
                () -> GetOaiPmhIdentifiers.parseArgs(
                        "--unknown-option"));
    }

    // ── Constructor / wiring ──────────────────────────────────────────────────

    @Test
    void constructorAcceptsNullOutputDir() {
        // null outputDir is valid for tests that never write files
        // (e.g. buildGetRecordUrl, parseArgs); the field is only accessed
        // inside writeGuidsFile, which is only called after a network fetch.
        assertDoesNotThrow(() -> new GetOaiPmhIdentifiers(
                URI.create("https://example.org/oai"), "ListIdentifiers", "oai_dc", null));
    }

    // ── Integration-style: write guids file via fetchIdentifiersForSet ──
    // These tests use a WireMock or local HTTP server in a real project;
    // here we verify behaviour that does not require network access.

    @Test
    void fetchAllSetIdentifiersWithEmptySetsArrayDoesNotThrow() {
        // With an empty set array the loop exits immediately without any
        // HTTP calls, so no exception should be thrown even without a server.
        GetOaiPmhIdentifiers noOpClient = new GetOaiPmhIdentifiers(
                URI.create("https://127.0.0.1:1"), "ListIdentifiers", "oai_ddi25", null);
        assertDoesNotThrow(
                () -> noOpClient.fetchAllSetIdentifiers(Collections.emptyList()));
    }

    // ── Parameterised: URL encoding covers all default sets ──────────────────

    @ParameterizedTest
    @ValueSource(strings = {"de", "el", "en", "fi", "fr", "hr", "nl",
                            "sl", "sl-SI", "sv"})
    void buildGetRecordUrlIsValidForEachDefaultSet(String set,
                                                   @TempDir Path dir) {
        GetOaiPmhIdentifiers c = new GetOaiPmhIdentifiers(
                GetOaiPmhIdentifiers.DEFAULT_OAI_PMH_BASE_URL,
                GetOaiPmhIdentifiers.DEFAULT_VERB,
                GetOaiPmhIdentifiers.DEFAULT_METADATA_PREFIX,
                dir);
        URI url = c.buildGetRecordUrl(set);
        assertTrue(url.isAbsolute(),
                "URL for set " + set + " must be absolute");
        assertTrue(url.getQuery().contains("verb=GetRecord"),
                "URL for set " + set + " must contain verb=GetRecord");
    }
}