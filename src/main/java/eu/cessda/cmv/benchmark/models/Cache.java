package eu.cessda.cmv.benchmark.models;

import java.util.Map;

public record Cache(
        String configFingerprint,
        Map<String, CachedRecord> files
) {
}
