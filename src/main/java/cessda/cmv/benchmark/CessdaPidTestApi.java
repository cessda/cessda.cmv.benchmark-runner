package cessda.cmv.benchmark;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.servers.Server;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@OpenAPIDefinition(
    info = @Info(
        version = "1.0.0",
        title = "CESSDA: Test for use of approved PID type (ARK, DOI, Handle, URN)",
        description = "Metric to test if the metadata resource has a PID of the approved type. This is done by comparing the PID to the patterns (by regexp) of approved PID schemas, as specified in the CessdaPersistentIdentifierTypes vocabulary ( https://vocabularies.cessda.eu/vocabulary/CessdaPersistentIdentifierTypes)",
        contact = @Contact(
            name = "John W Shepherdson",
            email = "john.shepherdson@cessda.eu",
            url = "https://cessda.eu/"
        ),
        extensions = {
            @Extension(name = "x-tests_metric", properties = {
                @ExtensionProperty(name = "value", value = "https://doi.org/10.25504/FAIRsharing.XXX")
            }),
            @Extension(name = "x-applies_to_principle", properties = {
                @ExtensionProperty(name = "value", value = "https://w3id.org/fair/principles/latest/XXX")
            })
        }
    ),
    servers = {
        @Server(url = "https://tests.ostrails.eu/assess/test")
    }
)
@RestController
public class CessdaPidTestApi {

    @PostMapping(
        value = "/fc_cessda_identifier",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @ApiResponse(
        responseCode = "200", 
        description = "The response is \"pass\", \"fail\" or \"indeterminate\"",
        content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = String.class)
        )
    )
    public ResponseEntity<String> cessdaPidResponse(
        @Valid @RequestBody CessdaPidRequest request
    ) {
        // Implementation logic goes here
        // This would contain the actual validation logic for GUIDs
        
        // Example response - replace with actual implementation
        return ResponseEntity.ok("\"pass\"");
    }

    /**
     * Request schema for CESSDA PID testing
     */
    @Schema(description = "Request body for testing CESSDA PID")
    public static class CessdaPidRequest {
        
        @Schema(
            description = "the GUID being tested",
            example = "https://doi.org/10.1000/182"
        )
        @NotNull
        private String resourceIdentifier;

        // Constructors
        public CessdaPidRequest() {}

        public CessdaPidRequest(String resourceIdentifier) {
            this.resourceIdentifier = resourceIdentifier;
        }

        // Getters and setters
        public String getResourceIdentifier() {
            return resourceIdentifier;
        }

        public void setResourceIdentifier(String resourceIdentifier) {
            this.resourceIdentifier = resourceIdentifier;
        }
    }

    /**
     * Response schema for CESSDA PID testing
     */
    @Schema(description = "Response indicating test result")
    public static class UniqueIdentifierResponse {
        
        @Schema(
            description = "Test result",
            allowableValues = {"pass", "fail", "indeterminate"},
            example = "pass"
        )
        private String result;

        // Constructors
        public String cessdaPidResponse() {
            return result;
        }

        public String cessdaPidResponse(String result) {
            this.result = result;
            return result;
        }

        // Getters and setters
        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }
    }
}