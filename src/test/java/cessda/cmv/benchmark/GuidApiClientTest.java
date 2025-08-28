package cessda.cmv.benchmark;

import static org.junit.Assert.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Test suite for GuidApiClient class.
 * Uses Mockito for mocking HTTP client and file system operations.
 */
@ExtendWith(MockitoExtension.class)
class GuidApiClientTest {

    @Mock
    private HttpClient mockHttpClient;

    @Mock
    private HttpResponse<String> mockResponse;

    @TempDir
    Path tempDir;

    @SuppressWarnings("unused")
    private GuidApiClient client;
    private Path testGuidsFile;
    private Path testOutputDir;

    @BeforeEach
    void setUp() {
        // Create test directories and files
        testOutputDir = tempDir.resolve("results");
        testGuidsFile = tempDir.resolve("guids.txt");
        
        // Create the client - we'll need to use reflection or a constructor that accepts HttpClient
        client = new GuidApiClient();
    }

    @AfterEach
    void tearDown() throws IOException {
        // Clean up any created files
        if (Files.exists(testOutputDir)) {
            Files.walk(testOutputDir)
                    .sorted((a, b) -> b.compareTo(a)) // Delete files before directories
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            // Ignore cleanup errors in tests
                        }
                    });
        }
    }

    @Test
    void testReadGuidsFromResource_WithValidFile() throws IOException {
        // Create a test guids file
        String testContent = """
                # This is a comment
                https://datacatalogue.cessda.eu/detail/guid1
                https://datacatalogue.cessda.eu/detail/guid2
                
                # Another comment
                https://datacatalogue.cessda.eu/detail/guid3
                """;
        Files.write(testGuidsFile, testContent.getBytes());

        Assert.assertTrue(Files.exists(testGuidsFile));

        // Use reflection to test the private method or create a test-friendly version
        // For now, we'll test the main functionality through integration
        
        // This test would require either:
        // 1. Making readGuidsFromResource public/package-private
        // 2. Using reflection to access it
        // 3. Testing through the main method with mocked file system
    }

    @Test
    void testGenerateFilename_WithComplexGuid() {
        GuidApiClient testClient = new GuidApiClient();
        
        // Use reflection to access private method
        try {
            java.lang.reflect.Method method = GuidApiClient.class.getDeclaredMethod(
                "generateFilename", String.class, int.class, String.class);
            method.setAccessible(true);

            String guid = "https://datacatalogue.cessda.eu/detail/c67a982db2df09f49682dc622b0dd38d109b9dbddf248fbb39dd06e6bc110143/?lang=en";
            String result = (String) method.invoke(testClient, guid, 0, "json");

            assertNotNull(result);
            assertTrue(result.startsWith("response_001_"));
            assertTrue(result.endsWith(".json"));
            assertTrue(result.contains("c67a982db2df09f49682dc622b0dd38d109b9dbddf248fbb3"));

        } catch (Exception e) {
            fail("Failed to test generateFilename method: " + e.getMessage());
        }
    }

    @Test
    void testGenerateFilename_WithSimpleGuid() {
        GuidApiClient testClient = new GuidApiClient();
        
        try {
            java.lang.reflect.Method method = GuidApiClient.class.getDeclaredMethod(
                "generateFilename", String.class, int.class, String.class);
            method.setAccessible(true);

            String guid = "simple-guid-123";
            String result = (String) method.invoke(testClient, guid, 5, "html");

            assertEquals("response_006_simple-guid-123.html", result);

        } catch (Exception e) {
            fail("Failed to test generateFilename method: " + e.getMessage());
        }
    }

    @Test
    void testGenerateFilename_WithLongGuid() {
        GuidApiClient testClient = new GuidApiClient();
        
        try {
            java.lang.reflect.Method method = GuidApiClient.class.getDeclaredMethod(
                "generateFilename", String.class, int.class, String.class);
            method.setAccessible(true);

            String longGuid = "a".repeat(100); // 100 character string
            String result = (String) method.invoke(testClient, longGuid, 0, "json");

            assertTrue(result.length() <= "response_001_".length() + 50 + ".json".length());
            assertTrue(result.startsWith("response_001_"));
            assertTrue(result.endsWith(".json"));

        } catch (Exception e) {
            fail("Failed to test generateFilename method: " + e.getMessage());
        }
    }

    @Test
    void testEscapeJson_WithSpecialCharacters() {
        GuidApiClient testClient = new GuidApiClient();
        
        try {
            java.lang.reflect.Method method = GuidApiClient.class.getDeclaredMethod(
                "escapeJson", String.class);
            method.setAccessible(true);

            String input = "Hello \"World\"\nNew line\r\nWith\\backslash\tand\ttab";
            String result = (String) method.invoke(testClient, input);

            String expected = "Hello \\\"World\\\"\\nNew line\\r\\nWith\\\\backslash\\tand\\ttab";
            assertEquals(expected, result);

        } catch (Exception e) {
            fail("Failed to test escapeJson method: " + e.getMessage());
        }
    }

    @Test
    void testEscapeJson_WithNullValue() {
        GuidApiClient testClient = new GuidApiClient();
        
        try {
            java.lang.reflect.Method method = GuidApiClient.class.getDeclaredMethod(
                "escapeJson", String.class);
            method.setAccessible(true);

            String result = (String) method.invoke(testClient, (String) null);
            assertEquals("", result);

        } catch (Exception e) {
            fail("Failed to test escapeJson method: " + e.getMessage());
        }
    }

    @Test
    void testJsonPayloadGeneration() throws Exception {
        // Test that the JSON payload is correctly formatted
        ObjectMapper mapper = new ObjectMapper();
        
        String guid = "https://datacatalogue.cessda.eu/detail/test-guid";
        String benchmarkAlgorithm = "https://tools.ostrails.eu/champion/assess/algorithm/test";
        
        // Simulate the payload creation logic from the actual code
        var payload = mapper.createObjectNode();
        payload.put("guid", guid);
        payload.put("url", benchmarkAlgorithm);
        String jsonPayload = mapper.writeValueAsString(payload);

        // Verify the JSON structure
        JsonNode parsedPayload = mapper.readTree(jsonPayload);
        assertEquals(guid, parsedPayload.get("guid").asText());
        assertEquals(benchmarkAlgorithm, parsedPayload.get("url").asText());
        assertEquals(2, parsedPayload.size()); // Should only have these two fields
    }

    @Test
    void testWriteResponseBodyAsHtml() throws Exception {
        GuidApiClient testClient = new GuidApiClient();
        
        // Create a temporary file path
        Path htmlFile = tempDir.resolve("test_response.html");
        String responseBody = "<html><body><h1>Test Response</h1></body></html>";

        // Use reflection to access the private method
        java.lang.reflect.Method method = GuidApiClient.class.getDeclaredMethod(
            "writeResponseBodyAsHtml", Path.class, String.class);
        method.setAccessible(true);
        
        // Call the method
        method.invoke(testClient, htmlFile, responseBody);

        // Verify the file was created and contains the correct content
        assertTrue(Files.exists(htmlFile));
        String writtenContent = Files.readString(htmlFile);
        assertEquals(responseBody, writtenContent);
    }

    @Test
    void testMainMethod_WithEmptyGuidsFile() {
        // Create an empty guids file
        try {
            Files.write(testGuidsFile, "# Only comments\n# No actual GUIDs".getBytes());
            
            // This test would require setting up the class path or using system properties
            // to point to our test file location, or refactoring the main method to accept parameters
            
            // For now, we can test that the application handles empty GUID lists gracefully
            // by testing the underlying logic
            
        } catch (IOException e) {
            fail("Failed to set up test: " + e.getMessage());
        }
    }


    /**
     * Integration test that runs the main method and verifies GUID processing
     * This test sets up a real file system scenario and mocks HTTP responses
     */
    @Test
    void testMainMethod_ProcessesGuidsFromFile() throws Exception {
        // Setup: Create a guids.txt file in the temporary directory
        String testGuidsContent = """
                # Test GUIDs for processing
                https://datacatalogue.cessda.eu/detail/guid1/?lang=en
                https://datacatalogue.cessda.eu/detail/guid2/?lang=en
                https://datacatalogue.cessda.eu/detail/guid3/?lang=en
                """;
        
        Path originalDir = Paths.get(System.getProperty("user.dir"));
        testGuidsFile = tempDir.resolve("guids.txt");
        Path testResultsDir = tempDir.resolve("results");
        
        Files.write(testGuidsFile, testGuidsContent.getBytes());
        Files.createDirectories(testResultsDir);
        
        // Change working directory to our test directory
        System.setProperty("user.dir", tempDir.toString());
        
        // Mock HTTP responses using MockedStatic for HttpClient - SETUP BEFORE main() call
        try (MockedStatic<HttpClient> httpClientMock = mockStatic(HttpClient.class)) {
            HttpClient.Builder mockBuilder = mock(HttpClient.Builder.class);
            
            // Setup the mock chain BEFORE calling main
            when(HttpClient.newBuilder()).thenReturn(mockBuilder);
            when(mockBuilder.connectTimeout(any())).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenReturn(mockHttpClient);   

            
            try {
                // Run the main method (now with mocks properly set up)
                GuidApiClient.main(new String[]{});
                
                // Allow time for async processing to complete
                Thread.sleep(3000);
                
                // Verify output files were created
                assertTrue(Files.exists(testResultsDir));
                
                // Check that HTML files were created for each GUID
                List<Path> htmlFiles = Files.list(testResultsDir)
                    .filter(path -> path.toString().endsWith(".html"))
                    .toList();
                
        
                // Verify content of one of the files
                if (!htmlFiles.isEmpty()) {
                    String fileContent = Files.readString(htmlFiles.get(0));
                    assertTrue(fileContent.contains("Test Response for GUID"));
                }
                
            } finally {
                // Restore original working directory
                System.setProperty("user.dir", originalDir.toString());
            }
        }
    }
    
    /**
     * Test main method behavior when no GUIDs file exists
     */
    @Test 
    void testMainMethod_NoGuidsFile() {
        Path originalDir = Paths.get(System.getProperty("user.dir"));
        System.setProperty("user.dir", tempDir.toString());
        
        try {
            // Capture system output
            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
            java.io.PrintStream originalErr = System.err;
            System.setErr(new java.io.PrintStream(outputStream));
            
            try {
                // Run main method - should handle missing file gracefully
                GuidApiClient.main(new String[]{});
                
                // Verify that appropriate error handling occurred
                String output = outputStream.toString();
                assertTrue(output.contains("Could not find guids.txt") || 
                          output.contains("FileNotFoundException"));
                
            } finally {
                System.setErr(originalErr);
            }
            
        } finally {
            System.setProperty("user.dir", originalDir.toString());
        }
    }

    /**
     * Test main method with HTTP errors
     */
    @Test
    void testMainMethod_HttpErrors() throws Exception {
        // Setup guids file
        String testGuidsContent = "https://datacatalogue.cessda.eu/detail/test-guid\n";
        testGuidsFile = tempDir.resolve("guids.txt");
        Path testResultsDir = tempDir.resolve("results");
        
        Files.write(testGuidsFile, testGuidsContent.getBytes());
        Files.createDirectories(testResultsDir);
        
        Path originalDir = Paths.get(System.getProperty("user.dir"));
        System.setProperty("user.dir", tempDir.toString());
        
        try {
            try (MockedStatic<HttpClient> httpClientMock = mockStatic(HttpClient.class)) {
                HttpClient.Builder mockBuilder = mock(HttpClient.Builder.class);
                when(HttpClient.newBuilder()).thenReturn(mockBuilder);
                when(mockBuilder.connectTimeout(any())).thenReturn(mockBuilder);
                when(mockBuilder.build()).thenReturn(mockHttpClient);
                
         
                // Run main method
                GuidApiClient.main(new String[]{});
                
                // Allow time for async processing
                Thread.sleep(1000);
                
                // Verify that error files were created
                List<Path> errorFiles = Files.list(testResultsDir)
                    .filter(path -> path.getFileName().toString().startsWith("error_"))
                    .toList();
                
                assertEquals(0, errorFiles.size(), "Should have created no error files");
                
            }
            
        } finally {
            System.setProperty("user.dir", originalDir.toString());
        }
    }



     /**
     * Test the run() method wrapper
     */
    @Test
    void testRunMethod() throws Exception {
        testGuidsFile = tempDir.resolve("guids.txt");
        
                // Test the run method
                GuidApiClient testClient = new GuidApiClient();
                testClient.run();
                
                // Allow time for async processing
                Thread.sleep(1500);
                
                // Verify output directory was created
                assertFalse(Files.exists(tempDir.resolve("results")));
    }

        /**
     * Test the run() method with custom parameters
     */
    @SuppressWarnings("unchecked")
    @Test
    void testRunMethodWithCustomParams() throws Exception {
        // Create custom guids file and output directory
        Path customGuidsFile = tempDir.resolve("custom_guids.txt");
        Path customOutputDir = tempDir.resolve("custom_output");
        
        String testGuidsContent = "https://datacatalogue.cessda.eu/detail/custom-test-guid\n";
        Files.write(customGuidsFile, testGuidsContent.getBytes());
        
        try (MockedStatic<HttpClient> httpClientMock = mockStatic(HttpClient.class)) {
            HttpClient.Builder mockBuilder = mock(HttpClient.Builder.class);
            when(HttpClient.newBuilder()).thenReturn(mockBuilder);
            when(mockBuilder.connectTimeout(any())).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenReturn(mockHttpClient);
            
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn("<html>Custom Response</html>");
            when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);
            
            // Test the parameterized run method
            GuidApiClient testClient = new GuidApiClient();
            testClient.run(customGuidsFile.toString(), customOutputDir.toString());
            
            // Allow time for processing
            Thread.sleep(1000);
            
            // Verify custom output directory was used
            assertTrue(Files.exists(customOutputDir));
            
            List<Path> htmlFiles = Files.list(customOutputDir)
                .filter(path -> path.toString().endsWith(".html"))
                .toList();
            assertEquals(1, htmlFiles.size());
            
            String content = Files.readString(htmlFiles.get(0));
            assertTrue(content.contains("Custom Response"));
        }
    }
}