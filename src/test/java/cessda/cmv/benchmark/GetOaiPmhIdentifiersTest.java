/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package cessda.cmv.benchmark;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link GetOaiPmhIdentifiers}.
 *
 * <p>HTTP calls are not made; all tests exercise parsing, URL
 * construction, file writing, and CLI argument handling using
 * in-memory data or temporary files.</p>
 */
class GetOaiPmhIdentifiersTest {

    // ── Fixture ──────────────────────────────────────────────────────────────

    private GetOaiPmhIdentifiers client;

    @BeforeEach
    void setUp() {
        client = new GetOaiPmhIdentifiers(
                GetOaiPmhIdentifiers.DEFAULT_OAI_PMH_BASE_URL,
                GetOaiPmhIdentifiers.DEFAULT_VERB,
                GetOaiPmhIdentifiers.DEFAULT_METADATA_PREFIX);
    }

    @AfterEach
    void tearDown() {
        client = null;
    }

    // ── Constants ────────────────────────────────────────────────────────────

    @Test
    void defaultBaseUrlIsNotBlank() {
        assertFalse(GetOaiPmhIdentifiers.DEFAULT_OAI_PMH_BASE_URL.isBlank(),
                "DEFAULT_OAI_PMH_BASE_URL must not be blank");
    }

    @Test
    void defaultVerbIsListIdentifiers() {
        assertEquals("ListIdentifiers", GetOaiPmhIdentifiers.DEFAULT_VERB);
    }

    @Test
    void defaultMetadataPrefixIsOaiDdi25() {
        assertEquals("oai_ddi25", GetOaiPmhIdentifiers.DEFAULT_METADATA_PREFIX);
    }

    @Test
    void defaultSetsContainsTenLanguages() {
        assertEquals(10, GetOaiPmhIdentifiers.DEFAULT_SETS.length);
    }

    @Test
    void defaultSetsContainsExpectedLanguageCodes() {
        List<String> sets = List.of(GetOaiPmhIdentifiers.DEFAULT_SETS);
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
        String url = client.buildGetRecordUrl("abc123");
        assertTrue(url.contains("verb=GetRecord"),
                "URL must contain verb=GetRecord");
    }

    @Test
    void buildGetRecordUrlContainsMetadataPrefix() {
        String url = client.buildGetRecordUrl("abc123");
        assertTrue(url.contains("metadataPrefix=oai_ddi25"),
                "URL must contain the default metadata prefix");
    }

    @Test
    void buildGetRecordUrlContainsEncodedIdentifier() {
        String url = client.buildGetRecordUrl("abc123");
        assertTrue(url.contains("identifier=abc123"),
                "URL must contain the identifier");
    }

    @Test
    void buildGetRecordUrlEncodesSpecialCharactersInIdentifier() {
        String url = client.buildGetRecordUrl("id with spaces & more");
        assertFalse(url.contains(" "),
                "URL must not contain raw spaces");
        assertFalse(url.contains("&more"),
                "URL must not contain unencoded ampersand in identifier");
    }

    @Test
    void buildGetRecordUrlStartsWithBaseUrl() {
        String url = client.buildGetRecordUrl("id1");
        assertTrue(url.startsWith(GetOaiPmhIdentifiers.DEFAULT_OAI_PMH_BASE_URL),
                "URL must start with the configured base URL");
    }

    @Test
    void buildGetRecordUrlWithCustomBaseUrl() {
        GetOaiPmhIdentifiers custom = new GetOaiPmhIdentifiers(
                "https://example.org/oai", "ListIdentifiers", "oai_dc");
        String url = custom.buildGetRecordUrl("xyz");
        assertTrue(url.startsWith("https://example.org/oai"),
                "URL must use the custom base URL");
        assertTrue(url.contains("metadataPrefix=oai_dc"),
                "URL must use the custom metadata prefix");
    }

    @Test
    void buildGetRecordUrlWithEmptyIdentifierProducesValidUrl() {
        String url = client.buildGetRecordUrl("");
        assertTrue(url.contains("identifier="),
                "URL must still contain the identifier parameter key");
    }

    // ── parseArgs ────────────────────────────────────────────────────────────

    @Test
    void parseArgsWithNoArgumentsReturnsDefaults() throws IOException {
        CommandLine cmd = GetOaiPmhIdentifiers.parseArgs(new String[]{});
        assertFalse(cmd.hasOption("fetch-set"),
                "fetch-set must not be set when no args are given");
        assertFalse(cmd.hasOption("fetch-all-sets"),
                "fetch-all-sets must not be set when no args are given");
    }

    @Test
    void parseArgsRecognisesFetchSetShortOption() throws IOException {
        CommandLine cmd = GetOaiPmhIdentifiers.parseArgs(new String[]{"-s", "de"});
        assertTrue(cmd.hasOption("fetch-set"));
        assertEquals("de", cmd.getOptionValue("fetch-set"));
    }

    @Test
    void parseArgsRecognisesFetchSetLongOption() throws IOException {
        CommandLine cmd = GetOaiPmhIdentifiers.parseArgs(
                new String[]{"--fetch-set", "en"});
        assertTrue(cmd.hasOption("fetch-set"));
        assertEquals("en", cmd.getOptionValue("fetch-set"));
    }

    @Test
    void parseArgsRecognisesFetchAllSetsShortOption() throws IOException {
        CommandLine cmd = GetOaiPmhIdentifiers.parseArgs(new String[]{"-F"});
        assertTrue(cmd.hasOption("fetch-all-sets"));
    }

    @Test
    void parseArgsRecognisesBaseUrlLongOption() throws IOException {
        CommandLine cmd = GetOaiPmhIdentifiers.parseArgs(
                new String[]{"--oai-pmh-base-url", "https://example.org/oai"});
        assertEquals("https://example.org/oai",
                cmd.getOptionValue("oai-pmh-base-url"));
    }

    @Test
    void parseArgsRecognisesMetadataPrefixShortOption() throws IOException {
        CommandLine cmd = GetOaiPmhIdentifiers.parseArgs(
                new String[]{"-m", "oai_dc"});
        assertEquals("oai_dc", cmd.getOptionValue("metadata-prefix"));
    }

    @Test
    void parseArgsRecognisesCustomSetsOption() throws IOException {
        CommandLine cmd = GetOaiPmhIdentifiers.parseArgs(
                new String[]{"-S", "de,en,fr"});
        assertEquals("de,en,fr", cmd.getOptionValue("sets"));
    }

    @Test
    void parseArgsThrowsOnUnrecognisedOption() {
        assertThrows(IOException.class,
                () -> GetOaiPmhIdentifiers.parseArgs(
                        new String[]{"--unknown-option"}));
    }

    // ── Logging helpers ──────────────────────────────────────────────────────

    @Test
    void logInfoDoesNotThrowForPlainMessage() {
        assertDoesNotThrow(
                () -> GetOaiPmhIdentifiers.logInfo("plain message"));
    }

    @Test
    void logInfoDoesNotThrowForFormattedMessage() {
        assertDoesNotThrow(
                () -> GetOaiPmhIdentifiers.logInfo("value: %d", 42));
    }

    @Test
    void logSevereDoesNotThrowForPlainMessage() {
        assertDoesNotThrow(
                () -> GetOaiPmhIdentifiers.logSevere("severe plain"));
    }

    @Test
    void logSevereDoesNotThrowForFormattedMessage() {
        assertDoesNotThrow(
                () -> GetOaiPmhIdentifiers.logSevere("error: %s", "oops"));
    }

    // ── Constructor / wiring ──────────────────────────────────────────────────

    @Test
    void constructorAcceptsNullSafeDefaults() {
        assertDoesNotThrow(() -> new GetOaiPmhIdentifiers(
                "https://example.org/oai", "ListIdentifiers", "oai_dc"));
    }

    // ── Integration-style: write guids file via fetchIdentifiersForLanguage ──
    // These tests use a WireMock or local HTTP server in a real project;
    // here we verify behaviour that does not require network access.

    @Test
    void fetchAllLanguageIdentifiersWithEmptySetsArrayDoesNotThrow(
            @TempDir Path tempDir) {
        // With an empty set array the loop exits immediately without any
        // HTTP calls, so no exception should be thrown even without a server.
        GetOaiPmhIdentifiers noOpClient = new GetOaiPmhIdentifiers(
                "https://127.0.0.1:1", "ListIdentifiers", "oai_ddi25");
        assertDoesNotThrow(
                () -> noOpClient.fetchAllLanguageIdentifiers(new String[]{}));
    }

    // ── Parameterised: URL encoding covers all default sets ──────────────────

    @ParameterizedTest
    @ValueSource(strings = {"de", "el", "en", "fi", "fr", "hr", "nl",
                            "sl", "sl-SI", "sv"})
    void buildGetRecordUrlIsValidForEachDefaultSet(String set) {
        GetOaiPmhIdentifiers c = new GetOaiPmhIdentifiers(
                GetOaiPmhIdentifiers.DEFAULT_OAI_PMH_BASE_URL,
                GetOaiPmhIdentifiers.DEFAULT_VERB,
                GetOaiPmhIdentifiers.DEFAULT_METADATA_PREFIX);
        String url = c.buildGetRecordUrl(set);
        assertTrue(url.startsWith("https://"),
                "URL for set " + set + " must be absolute");
        assertTrue(url.contains("verb=GetRecord"),
                "URL for set " + set + " must contain verb=GetRecord");
    }
}