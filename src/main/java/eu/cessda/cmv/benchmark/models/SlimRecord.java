package eu.cessda.cmv.benchmark.models;

import java.net.URI;
import java.util.List;
import java.util.Map;

public record SlimRecord(
        String identifier,
        URI testedguid,
        double netScore,
        int maturity,
        Map<String, TestResults> testResults,
        List<String> narratives,
        List<List<List<String>>> guidances
) {
    public record TestResults(
            String log,
            String result,
            float weight
    ) {
    }
}
