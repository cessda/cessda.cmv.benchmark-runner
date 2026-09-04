# RunBenchmarkAssessment

Reads GUID files produced by `GetOaiPmhIdentifiers` (each line is a
full OAI-PMH `GetRecord` URL) and submits every URL to the FAIR
Champion benchmark assessment API. One JSON result file is written per
GUID into a subdirectory of `results/` named after the input file
(minus its extension).

## Overview

`RunBenchmarkAssessment` reads one or more `guids_<set>.txt` files,
builds a JSON payload for each `GetRecord` URL, and POSTs it to a
configurable Champion API endpoint. Responses are saved as JSON files.
If the response body is not valid JSON it is wrapped in an envelope
object. A transient failure — an SSL handshake error, a timeout, an
HTTP 500/502/504, or Champion returning HTTP 200 with one or more
indicators it was too overloaded to actually evaluate — is retried
automatically; only once every attempt has failed is an error saved,
to a separate `error_*.json` file, so that a failed GUID does not
interrupt the rest of the batch. See
[Retry, backoff, and error output](#retry-backoff-and-error-output)
for details.

Processing runs on a virtual thread per GUID, with a small pause
before each submission after the first and at most 2 requests in
flight to Champion at any time — see
[HTTP behaviour](#http-behaviour).

## Default values

| Parameter             | Default value                                                 |
|-----------------------|---------------------------------------------------------------|
| GUIDs directory       | `./guids`                                                     |
| Output directory      | `./results`                                                   |

## Command-line options

```text
-r, --championUri <uri>    Overrides the championUri URI at runtime
-s, --spreadsheetUri <uri> Overrides the spreadsheetUri URI at runtime
-t, --tenant <tenant-id>   Resolve algorithm/runner and data/results
                            directories from tenants.config.<tenant-id>
                            (an explicit -s/-r still overrides the
                            resolved algorithm/runner). Omit for the
                            shared top-level benchmark.* defaults.
-p, --process-file <file>  Process a single named GUID file
-P, --process-all          Process all guids_XX.txt files for the
                            default set list
-g, --guid <url>           Process a single GetRecord URL supplied on
                            the command line
-f, --filename <file>      GUIDs filename for legacy single-file mode
                            (default: guids_hr.txt)
-h, --help                 Show the help message
```

## Operating modes

If none of the mode flags (`-p`, `-P`, `-g`) are given, the class
runs in legacy single-file mode and processes the file specified by
`-f` / `--filename` (defaulting to `guids_hr.txt`).

## Tenant-aware runs

Passing `-t` / `--tenant <tenant-id>` makes this CLI resolve that
tenant's configuration from `tenants.config.<tenant-id>` in
`application.yaml` — the same entry, and the same resolution logic,
that `BenchmarkService` uses for a REST request carrying that
tenant's `X-API-Key`:

- `algorithm` and `runner` come from the tenant's own config (falling
  back to its legacy `spreadsheetUri` / `championUri` alias fields if
  those are what's set).
- The GUIDs directory becomes `{benchmark.data-dir}/<tenant-id>/`, and
  the output directory becomes `{benchmark.results-dir}/<tenant-id>/`
  — the same per-tenant layout the web application and
  `GetOaiPmhIdentifiers` both use.

Precedence is: an explicit `-s` / `-r` flag always wins, then the
`-t`-resolved tenant config, then the shared top-level
`benchmark.algorithm` / `benchmark.runner` defaults. So `-t cessda -s
https://custom.example.org/algorithm` runs against `cessda`'s data and
results directories using a one-off algorithm override.

If `-t` names a tenant ID with no `tenants.config` entry, the run logs
an error and exits before submitting anything.

This is intended for driving a per-tenant run from outside the web
application — for example, from an external QA or CI pipeline — while
still keeping each tenant's data, results, and FAIR Champion
configuration separate. Note that this is separate from
`GetOaiPmhIdentifiers`'s own `-t` / `--tenant` flag: that flag only
selects an output subdirectory for fetched identifiers and does not
affect which OAI-PMH endpoint is queried (see
[GetOaiPmhIdentifiers_README.md](GetOaiPmhIdentifiers_README.md)).

Registering tenant configuration for this CLI means it now performs
the same startup validation the REST API's context does: if any
`tenants.config` entry in `application.yaml` is missing a `title` or
`footer`, the CLI fails to start — even for a run that never passes
`-t`. Every tenant already configured for the web application
satisfies this, since it is validated there too.

## Runtime configuration

The shared, non-tenant-scoped endpoint URIs are configured via
`application.yaml` and can be overridden at runtime without
recompilation:

| Property                                    | Environment variable                         | Purpose                       |
|----------------------------------------------|-----------------------------------------------|-------------------------------|
| `benchmark.algorithm`                        | `BENCHMARK_ALGORITHM`                         | Benchmark algorithm URI (payload), used when `-t` is not given |
| `benchmark.runner`                           | `BENCHMARK_RUNNER`                            | Champion runner URI (POST target), used when `-t` is not given |
| `benchmark.data-dir`                         | `BENCHMARK_DATA_DIR`                          | Root GUIDs directory; resolved as `{data-dir}/<tenant-id>/` when `-t` is given |
| `benchmark.results-dir`                      | `BENCHMARK_RESULTS_DIR`                       | Root results directory; resolved as `{results-dir}/<tenant-id>/` when `-t` is given |
| `benchmark.backoff-between-process-guid-ms`  | `BENCHMARK_BACKOFF_BETWEEN_PROCESS_GUID_MS`   | Pause, in milliseconds, before submitting each GUID after the first in a batch (default: 1000). Leave unset to keep the compiled-in default. |

For example, using environment variables:

```bash
BENCHMARK_ALGORITHM=https://custom.example.org/algorithm \
BENCHMARK_RUNNER=https://custom.example.org/runner \
java -jar target/benchmark-1.0-SNAPSHOT.jar --process-all
```

Or JVM system properties:

```bash
java -Dbenchmark.algorithm=https://... \
     -Dbenchmark.runner=https://... \
     -jar target/benchmark-1.0-SNAPSHOT.jar --process-all
```

## Usage

### Process all default GUIDs

Run with Maven (from the project root):

```bash
mvn exec:java \
  -Dexec.mainClass="cessda.cmv.benchmark.RunBenchmarkAssessment" \
  -Dexec.args="--process-all"
```

Run from a JAR:

```bash
java -jar target/benchmark-1.0-SNAPSHOT.jar cessda.cmv.benchmark.RunBenchmarkAssessment \
  --process-all
```

### Process a single GUID file

```bash
mvn exec:java \
  -Dexec.mainClass="cessda.cmv.benchmark.RunBenchmarkAssessment" \
  -Dexec.args="--process-file guids_de.txt"
```

```bash
java -jar target/benchmark-1.0-SNAPSHOT.jar cessda.cmv.benchmark.RunBenchmarkAssessment \
  --process-file guids_de.txt
```

### Process a single GetRecord URL

```bash
mvn exec:java \
  -Dexec.mainClass="cessda.cmv.benchmark.RunBenchmarkAssessment" \
  -Dexec.args="--guid https://datacatalogue.cessda.eu/oai-pmh/v0/oai?verb=GetRecord&metadataPrefix=oai_ddi25&identifier=abc123"
```

```bash
java -jar target/benchmark-1.0-SNAPSHOT.jar cessda.cmv.benchmark.RunBenchmarkAssessment \
  --guid "https://datacatalogue.cessda.eu/oai-pmh/v0/oai\
?verb=GetRecord&metadataPrefix=oai_ddi25&identifier=abc123"
```

### Process all default GUIDs for one tenant

```bash
mvn exec:java \
  -Dexec.mainClass="cessda.cmv.benchmark.RunBenchmarkAssessment" \
  -Dexec.args="--tenant cessda --process-all"
```

```bash
java -jar target/benchmark-1.0-SNAPSHOT.jar cessda.cmv.benchmark.RunBenchmarkAssessment \
  --tenant cessda --process-all
```

This resolves `cessda`'s `algorithm` and `runner` from
`tenants.config.cessda`, reads `guids_XX.txt` files from
`{benchmark.data-dir}/cessda/`, and writes results under
`{benchmark.results-dir}/cessda/` — see
[Tenant-aware runs](#tenant-aware-runs).

### Use a custom Champion API endpoint

```bash
mvn exec:java \
  -Dexec.mainClass="cessda.cmv.benchmark.RunBenchmarkAssessment" \
  -Dexec.args="--spreadsheet https://custom.example.org/champion/assess/... \
  --process-all"
```

```bash
java -jar target/benchmark-1.0-SNAPSHOT.jar cessda.cmv.benchmark.RunBenchmarkAssessment \
  --spreadsheet https://custom.example.org/champion/assess/... \
  --process-all
```

## Input files

Each input file must contain one `GetRecord` URL per line. Lines that
are blank or begin with `#` are skipped. The files are looked up first
on the classpath (resources), then in the current working directory.

A typical file produced by `GetOaiPmhIdentifiers` looks like:

```text
# Identifiers for set: de
# Fetched: 2026-01-01T00:00:00Z
# Count: 3
https://datacatalogue.cessda.eu/oai-pmh/v0/oai?verb=GetRecord
  &metadataPrefix=oai_ddi25&identifier=abc
https://datacatalogue.cessda.eu/oai-pmh/v0/oai?verb=GetRecord
  &metadataPrefix=oai_ddi25&identifier=def
```

## Output files

All output is written under the `results/` directory (or, with `-t` /
`--tenant`, under `{benchmark.results-dir}/<tenant-id>/` — see
[Tenant-aware runs](#tenant-aware-runs)), which is created
automatically. When processing a named file, a subdirectory is created
from the filename with its extension removed, e.g. processing
`guids_de.txt` writes results to `results/guids_de/`.

For each GUID, the output filename is derived from the `identifier=`
query parameter value. Characters that are not alphanumeric, dots,
underscores, or hyphens are replaced with underscores.

Successful responses are saved as `<sanitised-identifier>.json`. If
the API returns a non-JSON body, the response is wrapped:

```json
{
  "guid": "<GetRecord URL>",
  "statusCode": 200,
  "responseType": "html",
  "content": "<raw response body>",
  "timestamp": "2026-01-01T00:00:00Z"
}
```

Errors are saved as `error_<sanitised-guid>.json` — note this is the
whole `GetRecord` URL sanitised the same way, not just the
`identifier=` value, so error filenames are longer than their
corresponding result filenames would be:

```json
{
  "guid": "<GetRecord URL>",
  "error": "<exception message>",
  "errorType": "IOException",
  "timestamp": "2026-01-01T00:00:00Z",
  "cause": "<cause message>"
}
```

If every retry attempt still found an overwhelmed indicator in
Champion's response (see
[Retry, backoff, and error output](#retry-backoff-and-error-output)),
`errorType` is `"OverwhelmedIndicatorException"` and the file also
carries the specific indicator name(s):

```json
{
  "guid": "<GetRecord URL>",
  "error": "Champion response for GUID <url> contained overwhelmed indicator(s): F1_GUID, A1_1",
  "errorType": "OverwhelmedIndicatorException",
  "timestamp": "2026-01-01T00:00:00Z",
  "overwhelmedIndicators": ["F1_GUID", "A1_1"]
}
```

Exactly one error file is written per GUID that ultimately fails —
whichever of the checks above caught the failure saves it, so
`errorType`/`overwhelmedIndicators` alone are enough to tell failure
kinds apart without parsing the `error` message text.

## How it works

1. GUIDs are read from the selected file(s) or supplied directly.
2. A JSON payload is built: `{"calculation_uri": "<spreadsheetUri>", "guid": "<url>"}`.
3. The payload is POSTed to the Champion championUri URI, retrying
   transient failures with backoff (see below).
4. The response body is saved as a JSON file.
5. On error, an error JSON file is saved and processing continues
   with the next GUID.

## Retry, backoff, and error output

Two independent kinds of pacing exist, for two different problems:

- **Between GUIDs.** Before submitting each GUID after the first in a
  batch, processing pauses for
  `benchmark.backoff-between-process-guid-ms` (default 1000ms) — see
  [Runtime configuration](#runtime-configuration). This spreads the
  batch out over time so Champion sees a steadier request rate rather
  than a burst.
- **Per request, on failure.** A single GUID's request is retried up
  to 3 times with exponential backoff (2s, 4s, 8s between attempts)
  when:
  - the connection fails with an SSL handshake error, or times out;
  - Champion responds with HTTP 500, 502, or 504;
  - Champion responds HTTP 200, but the body contains one or more
    indicators whose `"result"` reads `"indeterminate (result data
    not found)"` with a `null` `"log"` — Champion's way of saying it
    was too overloaded to actually evaluate that indicator, rather
    than a genuine indeterminate outcome. A response like this is
    never written to disk as a result file; it's treated exactly
    like a 500/502/504 and retried instead.

  Any other `IOException` (e.g. a failure writing the result to disk)
  fails immediately without retrying. If every attempt is exhausted,
  one `error_<sanitised-guid>.json` is written (see
  [Output files](#output-files)) and processing moves on to the next
  GUID — a single GUID failing never stops the batch.

## HTTP behaviour

- Connection timeout: 30 seconds.
- Request timeout per GUID: 120 seconds.
- Up to 3 attempts per GUID, exponential backoff (2s/4s/8s) between
  them — see [Retry, backoff, and error output](#retry-backoff-and-error-output).
- Concurrency: one virtual thread per GUID, but at most 2 requests in
  flight to Champion at any time (a semaphore gates the rest, so a
  large batch doesn't hit Champion with hundreds of simultaneous
  requests even though every GUID's task starts right away).
- Executor shutdown timeout: 10 minutes for the whole batch.

## Dependencies

- Java standard library (`java.net.http`, `java.util.concurrent`)
- Apache Commons CLI (argument parsing)
- Jackson Databind (JSON serialisation)

## Contributing

Please read [CONTRIBUTING](CONTRIBUTING.md) for details on our code of conduct,
and the process for submitting pull requests to us.

## Versioning

See [Semantic Versioning](https://semver.org/) for guidance.

## Contributors

You can find the list of contributors in the [CONTRIBUTORS](CONTRIBUTORS.md)
file.

## License

See the [LICENSE](LICENSE.txt) file.

## Citing

See the [CITATION](CITATION.cff) file.
