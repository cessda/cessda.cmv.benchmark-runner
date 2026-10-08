package eu.cessda.cmv.benchmark.models;

import java.net.URI;
import java.time.Instant;

public record Response(
        URI guid,
        int statusCode,
        // TODO convert to enum
        String responseType,
        String responseBody,
        Instant timestamp
) {
}
