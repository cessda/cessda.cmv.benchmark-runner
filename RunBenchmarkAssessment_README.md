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
object. Errors are captured and saved to separate `error_*.json` files
so that a failed GUID does not interrupt the rest of the batch.

Processing is parallelised with a fixed thread pool of five workers.

## Default values

| Parameter        | Default value                                                 |
|------------------|------------------------------------------------------------   |
| Champion API URI | `https://tools.ostrails.eu/champion/assess/algorithm/`...     |
| GUIDs file       | `guids_hr.txt`                                                |
| Sets             | `de`, `el`, `en`, `fi`, `fr`, `hr`, `nl`, `sl`, `sl-SI`, `sv` |
| Output directory | `results/`                                                    |

## Command-line options

```text
-s, --spreadsheet <uri>    FAIR Champion API URI to POST GUIDs to
                            (default: BENCHMARK_ALGORITHM_URI constant)
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

## Usage

Run with Maven (from the project root):

```bash
mvn exec:java \
  -Dexec.mainClass="cessda.cmv.benchmark.RunBenchmarkAssessment" \
  -Dexec.args="--process-all"
```

Run from a JAR:

```bash
java -cp <jar> cessda.cmv.benchmark.RunBenchmarkAssessment \
  --process-all
```

### Process all default sets

```bash
java -cp <jar> cessda.cmv.benchmark.RunBenchmarkAssessment \
  --process-all
```

### Process a single GUID file

```bash
java -cp <jar> cessda.cmv.benchmark.RunBenchmarkAssessment \
  --process-file guids_de.txt
```

### Process a single GetRecord URL

```bash
java -cp <jar> cessda.cmv.benchmark.RunBenchmarkAssessment \
  --guid "https://datacatalogue.cessda.eu/oai-pmh/v0/oai\
?verb=GetRecord&metadataPrefix=oai_ddi25&identifier=abc123"
```

### Use a custom Champion API endpoint

```bash
java -cp <jar> cessda.cmv.benchmark.RunBenchmarkAssessment \
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

All output is written under the `results/` directory, which is created
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

Errors are saved as `error_<sanitised-identifier>.json`:

```json
{
  "guid": "<GetRecord URL>",
  "error": "<exception message>",
  "errorType": "IOException",
  "timestamp": "2026-01-01T00:00:00Z",
  "cause": "<cause message>"
}
```

## How it works

1. GUIDs are read from the selected file(s) or supplied directly.
2. A JSON payload is built: `{"guid": "<url>", "url": "<api-uri>"}`.
3. The payload is POSTed to the Champion API.
4. The response body is saved as a JSON file.
5. On error, an error JSON file is saved and processing continues
   with the next GUID.

## HTTP behaviour

- Connection timeout: 30 seconds.
- Request timeout per GUID: 60 seconds.
- Thread pool: 5 workers.
- Executor shutdown timeout: 10 minutes.

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
