package eu.cessda.cmv.benchmark.models;

import java.net.URI;
import java.nio.file.Path;

/**
 * The algorithm, runner, and data/results directories resolved for
 * one tenant by {@link #resolveTenant}.
 *
 * @param algorithm  the tenant's effective algorithm URI
 * @param runner     the tenant's effective runner URI
 * @param dataDir    {@code {data-dir}/{tenantId}/}, absolute and
 *                   normalized
 * @param resultsDir {@code {results-dir}/{tenantId}/}, absolute and
 *                   normalized
 */
public record TenantResolution(URI algorithm, URI runner, Path dataDir, Path resultsDir) {
}
