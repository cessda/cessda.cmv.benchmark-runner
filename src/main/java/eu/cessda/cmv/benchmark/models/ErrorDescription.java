package eu.cessda.cmv.benchmark.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import eu.cessda.cmv.benchmark.OverwhelmedIndicatorException;

import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorDescription(
        URI guid,
        String error,
        String errorType,
        Instant timestamp,
        String cause,
        List<String> overwhelmedIndicators
) {
    public ErrorDescription(
            URI guid,
            Throwable error
    ) {
        this(
                guid,
                error.getMessage(),
                error.getClass().getSimpleName(),
                Instant.now(),
                error.getCause() != null
                        ? error.getCause().getMessage()
                        : null,
                error instanceof OverwhelmedIndicatorException overwhelmed
                        ? overwhelmed.getIndicators()
                        : Collections.emptyList()
        );
    }
}
