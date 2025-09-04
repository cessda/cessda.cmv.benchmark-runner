package cessda.cmv.benchmark;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;


/**
 * Client for interacting with the GUID API.
 * This class reads GUIDs from a file, sends them to the API,
 * and saves the responses to files.
 */
public class GuidApiClient {

    private static final String BENCHMARK_ALGORITHM_URI = "https://tools.ostrails.eu/champion/assess/algorithm/1Nk0vM4yBpVQTo_UbB62NY_fz93aRZRHBZGh5fG-khOw";
    private static final String GUIDS_FILE = "guids.txt";
    private static final String OUTPUT_DIR = "results";
   
    private static final String HEADER_VALUE = "application/json";
    private static final String ACCEPT = "Accept";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String RESPONSE = "response";  
    private static final String NOGUIDS = "No GUIDs found to process. Exiting.";
    private static final String PROCCOMP = "Processing completed!";
    private static final String FOUNDGUIDS = "Found %d GUIDs to process";
    private static final String ERROR = "error_";
    private static final String STATUS = " (Status: ";
    private static final String PROCFAIL = "✗ Failed to process GUID ";
    private static final String RESPSAVED = "✓ Saved response for GUID ";
    private static final String PROCERROR = "Error processing GUID %s: %s";
    private static final String TASKWAIT ="Waiting for all tasks to complete...";
    private static final String TASKTOOLONG = "Some tasks did not complete in time!";
    private static final String TASKSUCCESS = "All tasks completed successfully.";
    private static final String REQSEND = "Sending request to API with payload: ";
    private static final String FILESAVEERROR = "Could not save error file: ";

    private static Logger logger = Logger.getLogger(GuidApiClient.class.getName());

    // HTTP client with a 30-second connection timeout
    // and a 60-second request timeout
    // This client will be used to send requests to the API endpoint
    // and handle responses.
    // It is configured to handle timeouts and retries.
    // The client will be used to send requests to the API endpoint
    // and handle responses.
    private final HttpClient httpClient;

    /**
     * Constructor initializes the HTTP client with a 30-second connection timeout.
     */
    public GuidApiClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Main method to run the GUID processing workflow.
     * It reads GUIDs from a resource file, processes each GUID,
     * and saves the results to an output directory.
     *
     * @param args Command line arguments (not used)
     */
    public static void main(String[] args) {

        // Set the logging level
        logger.setLevel(Level.INFO);

        GuidApiClient client = new GuidApiClient();

        try {
            // Create output directory if it doesn't exist
            Files.createDirectories(Paths.get(OUTPUT_DIR));

            // Read GUIDs from resource file
            List<String> guids = client.readGuidsFromResource();

            if (guids.isEmpty()) {
                logger.info(NOGUIDS);
                return;
            } else if (logger.isLoggable(java.util.logging.Level.INFO)) {
                logger.info(String.format(FOUNDGUIDS, guids.size()));
            }

            // Process each GUID
            client.processGuids(guids, false, false, true);

            if (logger.isLoggable(java.util.logging.Level.INFO)) {
                logger.info(PROCCOMP);
            }

        } catch (IOException ioe) {
            if (logger.isLoggable(java.util.logging.Level.SEVERE)) {
                logger.severe("Error: " + ioe.getMessage());
                ioe.printStackTrace();
            }
        } catch (InterruptedException ie) {
            logger.severe("Processing was interrupted: " + ie.getMessage());
            Thread.currentThread().interrupt(); // Restore interrupted status
        }
    }

    /**
     * Reads GUIDs from a resource file or the current directory.
     * The file should contain one GUID per line, with optional comments starting with '#'.
     *
     * @return List of GUIDs
     * @throws IOException if the file cannot be read
     */
    private List<String> readGuidsFromResource() throws IOException {
        // Try to read from resources first
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(GUIDS_FILE)) {
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                    return reader.lines()
                            .map(String::trim)
                            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                            .toList();
                }
            }
        }

        // If not found in resources, try current directory
        Path guidsPath = Paths.get(GUIDS_FILE);
        if (Files.exists(guidsPath)) {
            return Files.readAllLines(guidsPath)
                    .stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        }

        throw new FileNotFoundException("Could not find " + GUIDS_FILE + " in resources or current directory");
    }

     /**
     * Enhanced version of processGuid that saves both HTML and JSON formats.
     * 
     * @param guid The GUID to process
     * @param index The index for filename generation
     * @param saveAsHtml Whether to save HTML format
     * @param saveAsJson Whether to save JSON format
     * @param saveAsStructuredJson Whether to save structured JSON format
     * @throws IOException if file operations fail
     * @throws InterruptedException if the request is interrupted
     */
    private void processGuidWithMultipleFormats(String guid, int index, boolean saveAsHtml, 
                                              boolean saveAsJson, boolean saveAsStructuredJson) 
            throws IOException, InterruptedException {
        
        if (logger.isLoggable(java.util.logging.Level.INFO)) {
            logger.info(String.format("Processing GUID %d: %s", (index + 1), guid));
        }

        // Create JSON payload
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("guid", guid);
        payload.put("url", BENCHMARK_ALGORITHM_URI);
        String jsonPayload = mapper.writeValueAsString(payload);

        if (logger.isLoggable(java.util.logging.Level.INFO)) {
            logger.info(REQSEND + jsonPayload);
        }

        // Record request start time
        java.time.Instant requestStart = java.time.Instant.now();

        // Create HTTP request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BENCHMARK_ALGORITHM_URI))
                .header(ACCEPT, HEADER_VALUE)
                .header(CONTENT_TYPE, HEADER_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(Duration.ofSeconds(60))
                .build();

        try {
            // Send request and measure time
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            
            long processingTimeMs = java.time.Duration.between(requestStart, java.time.Instant.now()).toMillis();

            // Save in multiple formats as requested
            if (saveAsHtml) {
                String htmlFilename = generateFilename(guid, index, "html");
                Path htmlOutputPath = Paths.get(OUTPUT_DIR, htmlFilename);
                writeResponseBodyAsHtml(htmlOutputPath, response.body());
            }

            if (saveAsJson) {
                String jsonFilename = generateFilename(guid, index, "json");
                Path jsonOutputPath = Paths.get(OUTPUT_DIR, jsonFilename);
                writeResponseBodyAsJson(jsonOutputPath, response.body(), guid, response.statusCode());
            }

            if (saveAsStructuredJson) {
                String structuredJsonFilename = generateFilename(guid, index, "structured.json");
                Path structuredJsonOutputPath = Paths.get(OUTPUT_DIR, structuredJsonFilename);
                writeStructuredJsonResponse(structuredJsonOutputPath, response.body(), guid, 
                                          response.statusCode(), requestStart, processingTimeMs);
            }

            if (logger.isLoggable(java.util.logging.Level.INFO)) {
                logger.info(RESPSAVED + (index + 1) + 
                           STATUS + response.statusCode() + ", Time: " + processingTimeMs + "ms)");
            }

        } catch (Exception e) {
            logger.severe(PROCFAIL + (index + 1) + ": " + e.getMessage());
            
            // Save error information
            saveErrorFile(guid, index, e);
            throw e; // Rethrow to allow outer handling
        }
    }

    /**
     * Saves error information to a JSON file.
     * 
     * @param guid The GUID that caused the error
     * @param index The index for filename generation
     * @param error The exception that occurred
     */
    private void saveErrorFile(String guid, int index, Exception error) {
        try {
            String errorFilename = ERROR + generateFilename(guid, index, "json");
            Path errorPath = Paths.get(OUTPUT_DIR, errorFilename);

            if (Files.exists(errorPath)) {
                errorFilename = ERROR + System.currentTimeMillis() + "_" + errorFilename;
                errorPath = Paths.get(OUTPUT_DIR, errorFilename);
            }

            ObjectMapper mapper = new ObjectMapper();
            ObjectNode errorJson = mapper.createObjectNode();
            errorJson.put("guid", guid);
            errorJson.put("error", error.getMessage());
            errorJson.put("errorType", error.getClass().getSimpleName());
            errorJson.put("timestamp", java.time.Instant.now().toString());
            
            // Add stack trace if needed for debugging
            if (error.getCause() != null) {
                errorJson.put("cause", error.getCause().getMessage());
            }

            String errorContent = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(errorJson);
            Files.write(errorPath, errorContent.getBytes());
            
            if (logger.isLoggable(java.util.logging.Level.INFO)) {
            logger.info(String.format("✓ Saved error details to %s", errorFilename));
            }

        } catch (IOException ioException) {
            logger.severe(FILESAVEERROR + ioException.getMessage());
        }
    }

    /**
     * Public method to process GUIDs with configurable output formats.
     * 
     * @param saveAsHtml Whether to save responses as HTML files
     * @param saveAsJson Whether to save responses as JSON files  
     * @param saveAsStructuredJson Whether to save responses as structured JSON with metadata
     * @throws IOException if file operations fail
     * @throws InterruptedException if processing is interrupted
     */
    public void runWithOutputFormats(boolean saveAsHtml, boolean saveAsJson, boolean saveAsStructuredJson) 
            throws IOException, InterruptedException {
        
        logger.info("Starting GUID processing with custom output formats...");
        
        // Create output directory if it doesn't exist
        Files.createDirectories(Paths.get(OUTPUT_DIR));

        // Read GUIDs from resource file
        List<String> guids = readGuidsFromResource();

        if (guids.isEmpty()) {
            logger.info(NOGUIDS);
            return;
        } else if (logger.isLoggable(java.util.logging.Level.INFO)) {
            logger.info(String.format(FOUNDGUIDS, guids.size()));
        }

        // Process each GUID with specified formats
        processGuids(guids, saveAsHtml, saveAsJson, saveAsStructuredJson);

        if (logger.isLoggable(java.util.logging.Level.INFO)) {
            logger.info(PROCCOMP);
        }
    }

    /**
     * Processes multiple GUIDs with configurable output formats.
     * 
     * @param guids List of GUIDs to process
     * @param saveAsHtml Whether to save as HTML
     * @param saveAsJson Whether to save as JSON
     * @param saveAsStructuredJson Whether to save as structured JSON
     * @throws InterruptedException if processing is interrupted
     */
    private void processGuids(List<String> guids, boolean saveAsHtml, 
                                       boolean saveAsJson, boolean saveAsStructuredJson) 
            throws InterruptedException {
        
        try (ExecutorService executor = Executors.newFixedThreadPool(5)) {
            for (int i = 0; i < guids.size(); i++) {
                final String guid = guids.get(i);
                final int index = i;

                CompletableFuture.runAsync(() -> {
                    try {
                        processGuidWithMultipleFormats(guid, index, saveAsHtml, saveAsJson, saveAsStructuredJson);
                    } catch (IOException ioe) {
                        logger.severe(String.format(PROCERROR, guid, ioe.getMessage()));
                        saveErrorFile(guid, index, ioe);
                        Thread.currentThread().interrupt();
                    } catch (InterruptedException ie) {
                        logger.severe(String.format(PROCERROR, guid, ie.getMessage()));
                        Thread.currentThread().interrupt();
                    }
                }, executor);
            }
            
            logger.info(TASKWAIT);
            executor.shutdown();
            
            if (!executor.awaitTermination(10, TimeUnit.MINUTES)) {
                logger.severe(TASKTOOLONG);
            } else {
                logger.info(TASKSUCCESS);
            }
        }
    }

    /** * Writes the response body to an HTML file.
     * The HTML file is named after the GUID
     * and saved in the output directory.
     * @param path The path to the HTML file
     * @param responseBody The response body to write
     */
     private void writeResponseBodyAsHtml(Path path, String responseBody) {

        // Write the response body to the HTML file
        try {
            Files.write(path, responseBody.getBytes());
            logger.info("✓ Saved response body for GUID to " + path.getFileName());
        } catch (IOException e) {
            logger.severe("✗ Failed to save HTML file for GUID: " + e.getMessage());
        }
    }

    /**
     * Writes the response body to a JSON file.
     * If the response is already valid JSON, it writes it directly.
     * If the response is HTML or other format, it wraps it in a JSON structure.
     * 
     * @param path The path to the JSON file
     * @param responseBody The response body to write
     * @param guid The original GUID for metadata
     * @param statusCode HTTP status code for metadata
     */
    private void writeResponseBodyAsJson(Path path, String responseBody, String guid, int statusCode) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String jsonContent;
            
            // Try to parse response as JSON first
            try {
                // Validate if it's already JSON
                mapper.readTree(responseBody);
                // If successful, response is already valid JSON
                jsonContent = responseBody;
            } catch (Exception e) {
                // Response is not JSON, wrap it in a JSON structure
                ObjectNode jsonWrapper = mapper.createObjectNode();
                jsonWrapper.put("guid", guid);
                jsonWrapper.put("statusCode", statusCode);
                jsonWrapper.put("responseType", "html"); // or detect type
                jsonWrapper.put("content", responseBody);
                jsonWrapper.put("timestamp", java.time.Instant.now().toString());
                
                jsonContent = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonWrapper);
            }
            
            Files.write(path, jsonContent.getBytes());
            logger.info("✓ Saved JSON response for GUID to " + path.getFileName());
            
        } catch (IOException e) {
            logger.severe("✗ Failed to save JSON file for GUID: " + e.getMessage());
        }
    }

     /**
     * Writes a structured JSON response file with metadata.
     * This method always creates a structured JSON format regardless of the original response format.
     * 
     * @param path The path to the JSON file
     * @param responseBody The response body content
     * @param guid The original GUID
     * @param statusCode HTTP status code
     * @param requestTimestamp When the request was made
     * @param processingTimeMs How long the request took in milliseconds
     */
    private void writeStructuredJsonResponse(Path path, String responseBody, String guid, 
                                           int statusCode, java.time.Instant requestTimestamp, long processingTimeMs) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode jsonResponse = mapper.createObjectNode();
            
            // Add metadata
            jsonResponse.put("guid", guid);
            jsonResponse.put("statusCode", statusCode);
            jsonResponse.put("requestTimestamp", requestTimestamp.toString());
            jsonResponse.put("responseTimestamp", java.time.Instant.now().toString());
            jsonResponse.put("processingTimeMs", processingTimeMs);
            
            // Determine content type and add content
            String contentType = detectContentType(responseBody);
            jsonResponse.put("contentType", contentType);
            
            if ("json".equals(contentType)) {
                // If response is JSON, parse it and add as object
                try {
                    JsonNode responseJson = mapper.readTree(responseBody);
                    jsonResponse.set(RESPONSE, responseJson);
                } catch (Exception e) {
                    // Fallback to string if JSON parsing fails
                    jsonResponse.put(RESPONSE, responseBody);
                    jsonResponse.put("parseError", e.getMessage());
                }
            } else {
                // For HTML, XML, or other formats, store as string
                jsonResponse.put(RESPONSE, responseBody);
            }
            
            // Add request details
            ObjectNode requestDetails = mapper.createObjectNode();
            requestDetails.put("endpoint", BENCHMARK_ALGORITHM_URI);
            requestDetails.put("method", "POST");
            jsonResponse.set("requestDetails", requestDetails);
            
            String jsonContent = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonResponse);
            Files.write(path, jsonContent.getBytes());
            
            logger.info("✓ Saved structured JSON response for GUID to " + path.getFileName());
            
        } catch (IOException e) {
            logger.severe("✗ Failed to save structured JSON file for GUID: " + e.getMessage());
        }
    }

    /**
     * Detects the content type of a response body.
     * 
     * @param responseBody The response content to analyze
     * @return Detected content type: "json", "html", "xml", or "text"
     */
    private String detectContentType(String responseBody) {
        if (responseBody == null || responseBody.trim().isEmpty()) {
            return "empty";
        }
        
        String trimmed = responseBody.trim();
        
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return "json";
        } else if (trimmed.startsWith("<!DOCTYPE html") || trimmed.startsWith("<html")) {
            return "html";
        } else if (trimmed.startsWith("<?xml") || trimmed.startsWith("<")) {
            return "xml";
        } else {
            return "text";
        }
    }

    /**
     * Generates a sanitised filename based on the GUID and index.
     * The filename is formatted as "response_001_guid.json" where
     * "guid" is a sanitised version of the GUID.
     *
     * @param guid  The GUID to base the filename on
     * @param index The index of the GUID in the list
     * @param suffix The file extension to use (e.g., "json", "html")
     * @throws IllegalArgumentException if the GUID is null or empty
     * @return A sanitized filename for saving the response
     */
    private String generateFilename(String guid, int index, String suffix) {
        // Extract a meaningful part from the GUID for filename
        String guidPart = guid;
        if (guid.contains("/detail/")) {
            guidPart = guid.substring(guid.indexOf("/detail/") + 8);
            if (guidPart.contains("/")) {
                guidPart = guidPart.substring(0, guidPart.indexOf("/"));
            }
            if (guidPart.contains("?")) {
                guidPart = guidPart.substring(0, guidPart.indexOf("?"));
            }
        }

        // Sanitize for filename
        String sanitized = guidPart.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (sanitized.length() > 50) {
            sanitized = sanitized.substring(0, 50);
        }

        return String.format("response_%03d_%s.%s", index + 1, sanitized, suffix);
    }

    /**
     * Runs the GUID processing workflow.
     * This is a convenience method that wraps the main method functionality
     * and can be called from other parts of the application or tests.
     * 
     * @throws IOException if file operations fail
     * @throws InterruptedException if processing is interrupted
     */
    public void run() throws IOException, InterruptedException {
        logger.info("Starting GUID processing workflow...");
        
        // Create output directory if it doesn't exist
        Files.createDirectories(Paths.get(OUTPUT_DIR));

        // Read GUIDs from resource file
        List<String> guids = readGuidsFromResource();

        if (guids.isEmpty()) {
            logger.info(NOGUIDS);
            return;
        } else if (logger.isLoggable(java.util.logging.Level.INFO)) {
            logger.info(String.format(FOUNDGUIDS, guids.size()));
        }

        // Process all GUID, saving only structured JSON format
        processGuids(guids, false, false, true);


        if (logger.isLoggable(java.util.logging.Level.INFO)) {
            logger.info(PROCCOMP);
        }
    }
}
