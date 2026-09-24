package eu.cessda.cmv.benchmark.models;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.net.URI;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Payload(
        URI calculationUri,
        URI guid
) {
}
