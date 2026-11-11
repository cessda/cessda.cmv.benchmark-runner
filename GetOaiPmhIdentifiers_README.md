# GetOaiPmhIdentifiers

Fetches identifier lists from an OAI-PMH endpoint and writes them as
full `GetRecord` URLs to `guids/<tenant>/guids_<set>.txt` files.

Each output line is a complete, ready-to-use OAI-PMH `GetRecord` URL,
for example:

```text
https://datacatalogue.cessda.eu/oai-pmh/v0/oai?verb=GetRecord
  &metadataPrefix=oai_ddi25&identifier=abc123
```

## Overview

`GetOaiPmhIdentifiers` queries an OAI-PMH endpoint using the
`ListIdentifiers` verb (or a configurable alternative), collecting all
record identifiers for one or more named sets. Pagination via
resumption tokens is handled automatically. The resulting identifiers
are converted into `GetRecord` URLs and written to plain-text files,
one file per set, under a tenant-scoped output directory — see
[Output files](#output-files).

## Default values

| Parameter       | Default value                                    |
|-----------------|--------------------------------------------------|
| Base URL        | `https://datacatalogue.cessda.eu/oai-pmh/v0/oai` |
| Metadata prefix | `oai_ddi25`                                      |
| Sets            |                                                  |
| Tenant          | `cessda`                                         |

## Command-line options

```text
-b, --oai-pmh-base-url <url>     OAI-PMH base URL
                                   (default: https://datacatalogue
                                   .cessda.eu/oai-pmh/v0/oai)
-m, --metadata-prefix <prefix>   Metadata prefix embedded in output
                                   GetRecord URLs (default: oai_ddi25)
-S, --sets <set1,set2,...>        Comma-separated list of sets to fetch
-t, --tenant <tenant>             Tenant name; output is written to
                                   guids/<tenant>/ (default: cessda)
-h, --help                        Show the help message
```

## Usage

Run with Maven (from the project root):

```bash
mvn exec:java \
  -Dexec.mainClass="eu.cessda.cmv.benchmark.GetOaiPmhIdentifiers" \
  -Dexec.args="--sets en"
```

Run from a JAR:

```bash
java -cp <jar> eu.cessda.cmv.benchmark.GetOaiPmhIdentifiers \
  --oai-pmh-base-url https://example.org/oai \
  --metadata-prefix oai_ddi25 \
  --sets de
```

### Fetch all sets (default)

```bash
java -cp <jar> eu.cessda.cmv.benchmark.GetOaiPmhIdentifiers
```

Or

```bash
mvn exec:java -Dexec.mainClass="eu.cessda.cmv.benchmark.GetOaiPmhIdentifiers"
```

### Fetch a custom list of sets

```bash
java -cp <jar> eu.cessda.cmv.benchmark.GetOaiPmhIdentifiers \
  --sets de,en,fr
```

### Fetch a single set

```bash
java -cp <jar> eu.cessda.cmv.benchmark.GetOaiPmhIdentifiers \
  --sets hr
```

### Fetch for a specific tenant

```bash
java -cp <jar> cessda.cmv.benchmark.GetOaiPmhIdentifiers \
  --tenant new-org \
  --sets de
```

Output is written to `guids/new-org/guids_de.txt` instead of the
default `guids/cessda/guids_de.txt`. See
[Output files](#output-files).

## Output files

For each set processed, a file named `guids_<set>.txt` is written to
`guids/<tenant>/`, where `<tenant>` is the value of `-t` /
`--tenant` (default: `cessda`). The directory is created automatically
if it does not already exist — e.g. running with the default tenant
writes to `guids/cessda/guids_de.txt`, `guids/cessda/guids_en.txt`,
and so on.

Each file begins with three comment lines:

```text
# Identifiers for set: de
# Fetched: 2026-01-01T00:00:00Z
# Count: 42
```

The remaining lines are full `GetRecord` URLs, one per identifier.

## How it works

`GetOaiPmhIdentifiers` uses the CESSDA Metadata Harvester's Java
API to implement OAI-PMH support. The logic uses is as follows:

1. A `ListIdentifiers` request is built from the base URL,
   metadata prefix, and set name.
2. The XML response is checked for a top-level `<error>` element (see
   [HTTP behaviour](#http-behaviour)) and then parsed for `<identifier>`
   elements.
3. If a resumption token is present, the next page is fetched and
   the process repeats until all pages are exhausted.
4. Each identifier is combined with the base URL and metadata
   prefix to produce a `GetRecord` URL.
5. All URLs are written to `guids_<set>.txt`.

## Discovering available sets (ListSets)

Besides fetching identifiers, `GetOaiPmhIdentifiers` can call
`verb=ListSets` to discover exactly which sets an endpoint currently
offers, returning each set's `setSpec`.

This is not currently exposed as its own CLI flag; it backs the
dashboard's "Fetch identifiers" page and the `GET
/api/fetch-identifiers/sets` REST endpoint. See
[ApiUsage_README.md](ApiUsage_README.md#discover-available-sets) for the REST
form.

## HTTP behaviour

- Connection timeout: 30 seconds.
- Request timeout: 60 seconds.
- OAI-PMH reports protocol-level failures (`badVerb`, `badArgument`,
  `noRecordsMatch`, etc.) as an `<error>` element inside an ordinary
  HTTP 200 response, not as a non-2xx status. An `<error>` element
  raises an `OAIException` with the OAI-PMH error code and message.

## Dependencies

- Java standard library (`java.net.http`, `javax.xml.parsers`)
- Apache Commons CLI (argument parsing)
- [CESSDA Metadata Harvester OAI-PMH API](https://github.com/cessda/cessda.metadata.harvester)

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
