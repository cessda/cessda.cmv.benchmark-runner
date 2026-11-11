package eu.cessda.cmv.benchmark.models;

import java.time.Instant;
import java.util.Map;

public record SummaryStats(
        Instant generated,
        SetStats overall,
        Map<String, SetStats> sets
) {
}
